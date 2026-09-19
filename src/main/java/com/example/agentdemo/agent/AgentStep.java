package com.example.agentdemo.agent;

/**
 * Agent 的一步：思考 → 行动 → 观察。
 * 手写版和框架版都产出这个结构，前端才能并排展示两条轨迹做对比。
 */
public record AgentStep(
        int step,
        String thought,      // 模型的思考（框架版拿不到中间自然语言，这里是 null）
        String action,       // 调用的工具名
        String input,        // 传给工具的输入
        String observation,  // 工具返回（也就是回填给模型的"事实"）
        long costMs,         // 这一步耗时
        String costScope     // 耗时口径：手写版含模型调用（model+tool），框架版只能测到工具（tool-only）
) {
}
