package com.example.agentdemo.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 短期记忆：一个会话（sessionId）内最近几轮聊了什么。
 *
 * 两件事必须想清楚，否则线上会出事：
 *
 * 1. **长度上限**。历史不是越长越好——每轮都拼进 prompt 就是每轮都付一次 token 钱，
 *    而且模型在长上下文里对前面内容的注意力会衰减。所以只保留最近 N 轮，
 *    超出的直接丢（这里是有意"遗忘"，不是 bug）。
 *
 * 2. **会话数量上限**。这是最容易漏的一条：Map 只增不减，每个新 sessionId 塞一条，
 *    跑一周就是内存泄漏。这里用 LinkedHashMap 的 accessOrder 做 LRU 淘汰：
 *    老会话被顶掉，宁可丢历史也不能把服务撑爆。
 *
 * 局限（面试要主动说）：**全在堆内存里**，进程重启即清空，多实例部署也不共享。
 * 要真上生产得换 Redis（会话 TTL）或数据库。这里的价值是把接口先定下来
 * （append / history），换存储时不影响 Agent。
 */
@Component
public class ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);

    /** 每个会话保留的最大轮数（1 轮 = 用户一句 + 助手一句） */
    private final int maxTurns;
    /** 最多同时保留多少个会话，超了淘汰最久没用的（LRU） */
    private final int maxSessions;

    /**
     * 会话的键：**用户 + 会话**，缺一不可。
     *
     * 只用 sessionId 做键是我最初写的版本，然后被自己的测试打脸了：
     * 两个用户（u9 / u10）恰巧用了同一个 sessionId 提问，u10 直接复述出了 u9 的部门。
     * 画像那边是按 userId 隔离的、没事；历史这边串了。
     *
     * 为什么会串：sessionId 是前端给的，它不受你控制。用户可以手输、可以从 URL 里拿、
     * 可以复用旧值——把它当唯一身份，等于把 A 的对话记录摊给 B 看。
     * 正确做法是键里必须带上登录态里的 userId，这样**猜中别人的 sessionId 也读不到东西**
     * （纵深防御：真正扛不住的是"谁都能拿到别人的 userId"，那得靠鉴权解决，不是靠这里的键）。
     */
    private record SessionKey(String userId, String sessionId) {
    }

    private final Map<SessionKey, List<ChatTurn>> sessions;

    public ConversationMemory(@Value("${agent.memory.max-turns:6}") int maxTurns,
                              @Value("${agent.memory.max-sessions:200}") int maxSessions) {
        this.maxTurns = maxTurns;
        this.maxSessions = maxSessions;
        // accessOrder=true：每次读也刷新位置，所以淘汰的是"最久没被访问"的会话，而不是最早创建的
        this.sessions = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<SessionKey, List<ChatTurn>> eldest) {
                boolean evict = size() > ConversationMemory.this.maxSessions;
                if (evict) {
                    log.info("会话数超过 {}，淘汰最久未访问的会话：{}", ConversationMemory.this.maxSessions, eldest.getKey());
                }
                return evict;
            }
        });
    }

    /** 追加一轮。超过上限就从头砍——保最近的 */
    public void append(String userId, String sessionId, ChatTurn turn) {
        synchronized (sessions) {
            List<ChatTurn> turns = sessions.computeIfAbsent(key(userId, sessionId), k -> new ArrayList<>());
            turns.add(turn);
            while (turns.size() > maxTurns * 2) {   // *2：一轮包含 user 和 assistant 两条
                turns.remove(0);
            }
        }
    }

    /** 取历史快照。返回副本，避免调用方拿着引用去改内部状态 */
    public List<ChatTurn> history(String userId, String sessionId) {
        synchronized (sessions) {
            List<ChatTurn> turns = sessions.get(key(userId, sessionId));
            return turns == null ? List.of() : List.copyOf(turns);
        }
    }

    public void clear(String userId, String sessionId) {
        sessions.remove(key(userId, sessionId));
    }

    private SessionKey key(String userId, String sessionId) {
        return new SessionKey(userId == null ? "" : userId, sessionId == null ? "" : sessionId);
    }

    /** 诊断用：当前有几个活跃会话 */
    public int sessionCount() {
        return sessions.size();
    }

    public int maxTurns() {
        return maxTurns;
    }
}
