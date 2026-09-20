package com.example.agentdemo.agent;

import java.util.List;
import java.util.Map;

import com.example.agentdemo.memory.ChatTurn;
import com.example.agentdemo.tools.MemorySaveTool;

/**
 * 一次运行的外部上下文：这个 Agent 是"谁"在"哪个会话"里跑的。
 *
 * 为什么要单独一个对象，而不是给 run() 加几个参数：
 *   1. 两个 Agent（手写 / 框架）都要用同一份上下文，加参数容易两边签名不一致
 *      （W4 已经栽过一次"两条路径指令不一样"的跟头，见 AgentPrompts 注释）；
 *   2. 以后加东西（租户、权限、语言偏好）只改这里，不动 Agent 的签名。
 *
 * Agent 读完这个对象**不改它**——记忆的写入由 Web 层在运行结束后统一做，
 * 这样 Agent 保持无状态（纯函数好测），记忆策略集中在一个地方好改。
 */
public record RunContext(
        String userId,
        String sessionId,
        /** 长期画像的渲染文本，没有则为 null（不占 prompt 空间） */
        String profile,
        /** 最近几轮历史，最早的在前面；没有就是空表 */
        List<ChatTurn> history
) {

    public static RunContext empty() {
        return new RunContext("", "", null, List.of());
    }

    /** 给工具的上下文：模型拿不到、只能由运行时注入的信息 */
    public Map<String, Object> toolContext() {
        return Map.of(MemorySaveTool.CTX_USER_ID, userId == null ? "" : userId);
    }
}
