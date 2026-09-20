package com.example.agentdemo.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 长期记忆：关于"这个人是谁"的稳定事实（部门、职位、入职时间、长期偏好）。
 *
 * 和 {@link ConversationMemory} 的分工：
 *   短期 = 这次聊了什么（会过期，按轮数丢）
 *   长期 = 这个人是谁（不该过期，跨会话保留）
 *
 * 两者混在一起会怎样：把"我是技术部的"和历史一起按轮数丢掉，
 * 下次开新会话它就不认识你了——明明这句话永远不会过期。
 *
 * **谁来写？** 两条路都行，这里选了"让模型自己决定"（见 MemoryTool）：
 * 工具调用的轨迹里看得见它记了什么，比在后台偷偷抽取好排查。
 * 代价是模型可能漏记或记些没用的——所以下面有长度上限兜底。
 */
@Component
public class UserProfileStore {

    private static final Logger log = LoggerFactory.getLogger(UserProfileStore.class);

    /** 单个用户最多记多少条事实。防止模型每轮都写一点、把画像撑爆 */
    private final int maxFacts;
    private final int maxUsers;

    private final Map<String, Map<String, String>> profiles;

    public UserProfileStore(@Value("${agent.memory.max-facts:20}") int maxFacts,
                            @Value("${agent.memory.max-users:200}") int maxUsers) {
        this.maxFacts = maxFacts;
        this.maxUsers = maxUsers;
        this.profiles = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) {
                return size() > UserProfileStore.this.maxUsers;
            }
        });
    }

    /**
     * 记一条。同名 key 直接覆盖（"我换部门了"应该更新而不是堆积）。
     * @return 失败原因；成功返回 null
     */
    public String put(String userId, String key, String value) {
        if (userId == null || userId.isBlank()) {
            return "没有传 userId，无法定位要记住谁";
        }
        if (key == null || key.isBlank()) {
            return "key 不能为空";
        }
        if (value == null || value.isBlank()) {
            return "value 不能为空";
        }
        synchronized (profiles) {
            Map<String, String> facts = profiles.computeIfAbsent(userId, k -> new LinkedHashMap<>());
            boolean isNew = !facts.containsKey(key);
            if (isNew && facts.size() >= maxFacts) {
                return "该用户画像已达上限 " + maxFacts + " 条，请先确认要覆盖哪一条（用相同的 key）";
            }
            facts.put(key, value);
            log.info("记住用户 {}：{} = {}", userId, key, value);
        }
        return null;
    }

    public Map<String, String> facts(String userId) {
        synchronized (profiles) {
            Map<String, String> facts = profiles.get(userId);
            return facts == null ? Map.of() : Map.copyOf(facts);
        }
    }

    /**
     * 渲染成可以塞进 system prompt 的文本。**没有画像时也要返回文字**，不能返回 null。
     *
     * 这是实测踩出来的：原先没有画像就直接不注入这一段，结果是用户问"我在哪个部门"，
     * 模型不知道自己是"没查到"还是"没这个能力"，于是编了一个听起来很合理的理由——
     * "我只有写入记忆的能力，没有读取个人信息的工具"。用户看到的是**一句自信的假话**。
     * 根因不是模型爱撒谎，是 prompt 里留了空白：缺信息时，你得明确告诉它"就是没有"。
     */
    public String render(String userId) {
        Map<String, String> facts = facts(userId);
        StringBuilder sb = new StringBuilder("你正在服务的用户，已知信息：\n");
        if (facts.isEmpty()) {
            sb.append("（暂无）你还不知道这个用户是谁。\n")
                    .append("需要这类信息就直接问用户；**不要猜，也不要解释成「你没有读取权限」或「你只能写不能读」**——\n")
                    .append("如实说「我这边还没有你的信息」即可。\n");
            return sb.toString();
        }
        for (Map.Entry<String, String> e : facts.entrySet()) {
            sb.append("- ").append(e.getKey()).append("：").append(e.getValue()).append('\n');
        }
        sb.append("这些是长期记忆，直接拿来用，不要再问用户一遍。\n");
        return sb.toString();
    }

    public List<String> keys(String userId) {
        return List.copyOf(facts(userId).keySet());
    }

    /** 清空某个用户的画像。演示/调试用——真实系统里"删除用户数据"是要走合规流程的正经功能 */
    public void clear(String userId) {
        if (userId != null) {
            profiles.remove(userId);
        }
    }
}
