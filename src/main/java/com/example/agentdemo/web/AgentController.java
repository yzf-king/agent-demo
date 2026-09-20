package com.example.agentdemo.web;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.agentdemo.agent.AgentResult;
import com.example.agentdemo.agent.NativeToolAgent;
import com.example.agentdemo.agent.ReActAgent;
import com.example.agentdemo.agent.RunContext;
import com.example.agentdemo.agent.ToolRegistry;
import com.example.agentdemo.memory.ChatTurn;
import com.example.agentdemo.memory.ConversationMemory;
import com.example.agentdemo.memory.UserProfileStore;

/**
 * 接口分两类，**别混用**：
 *
 *   有状态的（会读写记忆）：
 *     POST /api/agent/chat        多轮对话。带 sessionId / userId，回来还会附上记忆快照
 *   无状态的（每次都是新会话，只做诊断）：
 *     GET  /api/agent/compare     同一问题跑两条实现，并排看轨迹
 *     GET  /api/agent/react|native
 *
 * 为什么 compare 不写记忆：它是"同一个问题、两种实现"的对照实验，
 * 如果两边都往同一个会话里追加，第二次跑就会带着第一次的历史，变量就不干净了。
 * 记忆只由 /chat 写，写入点集中在一个地方——这也是把 Agent 设计成无状态的理由。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    /** 不传 userId 时的兜底。演示用；真实系统里这个值必须来自登录态，绝不能由前端随便给 */
    private static final String DEFAULT_USER = "demo-user";

    private final ReActAgent reactAgent;
    private final NativeToolAgent nativeToolAgent;
    private final ToolRegistry registry;
    private final ConversationMemory memory;
    private final UserProfileStore profiles;

    public AgentController(ReActAgent reactAgent, NativeToolAgent nativeToolAgent, ToolRegistry registry,
                           ConversationMemory memory, UserProfileStore profiles) {
        this.reactAgent = reactAgent;
        this.nativeToolAgent = nativeToolAgent;
        this.registry = registry;
        this.memory = memory;
        this.profiles = profiles;
    }

    @GetMapping("/tools")
    public List<ToolInfo> tools() {
        return registry.all().stream()
                .map(t -> new ToolInfo(t.name(), t.description()))
                .toList();
    }

    @GetMapping("/react")
    public AgentResult react(@RequestParam String q) {
        return reactAgent.run(q);
    }

    @GetMapping("/native")
    public AgentResult nativeAgent(@RequestParam String q) {
        return nativeToolAgent.run(q);
    }

    @GetMapping("/compare")
    public CompareView compare(@RequestParam String q) {
        return new CompareView(reactAgent.run(q), nativeToolAgent.run(q));
    }

    /**
     * 多轮对话入口。流程刻意分成"读记忆 → 跑 Agent → 写记忆"三步：
     * Agent 自己不碰记忆，所以它是纯函数（同样的 question + context 给同样的结果），
     * 记忆策略要改（比如换成 Redis、加摘要压缩）只动这一个方法。
     */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest req) {
        String sessionId = blankToNull(req.sessionId()) == null
                ? UUID.randomUUID().toString() : req.sessionId();
        String userId = blankToNull(req.userId()) == null ? DEFAULT_USER : req.userId();
        String question = req.question() == null ? "" : req.question().trim();
        if (question.isEmpty()) {
            throw new IllegalArgumentException("question 不能为空");
        }

        RunContext ctx = new RunContext(userId, sessionId, profiles.render(userId),
                memory.history(userId, sessionId));
        AgentResult result = "native".equalsIgnoreCase(req.mode())
                ? nativeToolAgent.run(question, ctx)
                : reactAgent.run(question, ctx);

        // 只把"用户问的"和"最终回答"写进历史。中间的工具 Observation 一律不写：
        // 那些片段动辄几百字，写进去几轮就把上下文顶满，而它们对下一轮的价值远不如一个结论
        memory.append(userId, sessionId, ChatTurn.user(question));
        memory.append(userId, sessionId, ChatTurn.assistant(result.answer()));

        return new ChatResponse(sessionId, userId, result,
                profiles.facts(userId), memory.history(userId, sessionId));
    }

    /** 看记忆里到底存了什么。调试用——Agent 记错了是最常见的毛病，得能当场看见 */
    @GetMapping("/memory")
    public MemoryView memory(@RequestParam(required = false) String userId,
                             @RequestParam(required = false) String sessionId) {
        String u = blankToNull(userId) == null ? DEFAULT_USER : userId;
        return new MemoryView(u, profiles.facts(u),
                sessionId == null ? List.of() : memory.history(u, sessionId),
                memory.sessionCount(), memory.maxTurns());
    }

    /** 清空记忆，方便反复演示同一段对话 */
    @DeleteMapping("/memory")
    public String clearMemory(@RequestParam(required = false) String userId,
                              @RequestParam(required = false) String sessionId) {
        String u = blankToNull(userId) == null ? DEFAULT_USER : userId;
        if (sessionId != null) {
            memory.clear(u, sessionId);
        }
        profiles.clear(u);
        return "已清空：用户 " + u + " 在会话 " + sessionId + " 的历史 + 该用户的画像";
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    public record ToolInfo(String name, String description) {
    }

    public record CompareView(AgentResult react, AgentResult nativeAgent) {
    }

    /** mode 留空默认走手写 ReAct（本项目的主角），传 "native" 走框架版 */
    public record ChatRequest(String sessionId, String userId, String question, String mode) {
    }

    public record ChatResponse(String sessionId, String userId, AgentResult result,
                               java.util.Map<String, String> profile, List<ChatTurn> history) {
    }

    public record MemoryView(String userId, java.util.Map<String, String> profile,
                             List<ChatTurn> history, int sessionCount, int maxTurns) {
    }
}
