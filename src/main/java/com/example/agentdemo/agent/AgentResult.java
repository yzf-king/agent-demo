package com.example.agentdemo.agent;

import java.util.List;

/** 一次 Agent 运行的完整结果：最终回答 + 走过的轨迹 */
public record AgentResult(
        String answer,
        List<AgentStep> steps,
        String mode,       // react / native，前端用来标注这是哪一种实现
        boolean finished,  // false = 达到步数上限也没得出 Final Answer（要如实告诉用户）
        long totalMs
) {
}
