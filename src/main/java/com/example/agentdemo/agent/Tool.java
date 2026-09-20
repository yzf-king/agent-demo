package com.example.agentdemo.agent;

import java.util.Map;

/**
 * Agent 能调用的一个工具。输入输出都是字符串是有意为之：
 *   - 手写 ReAct 走的是纯文本协议（模型吐出 "Action: calculator"，只能给字符串）
 *   - 框架版虽然内部是结构化参数，但对外也可以退化成字符串（见 NativeToolAgent）
 * 同一批实现被两种 Agent 复用，才能公平对比"框架到底帮我做了什么"。
 */
public interface Tool {

    /** 工具名：模型凭这个名字调用，必须和给它的工具清单一字不差 */
    String name();

    /** 给模型看的一句话说明：写得好不好直接决定它会不会用、用得对不对 */
    String description();

    /** 执行。输入是模型给的单行字符串，实现内部自己解析 */
    String run(String input);

    /**
     * 带上下文的执行：有些工具需要知道"是谁在问"（比如记用户画像），
     * 但这些信息**不该让模型来传**——模型凭什么知道你叫张三？它只会瞎编一个。
     * 所以走运行时上下文，由调用方（Web 层）填好。
     *
     * 默认转发到 {@link #run(String)}：不需要上下文的工具（计算器、时间、知识检索）
     * 一行都不用改。需要的才重写这个方法——接口演化时默认方法的价值就在这。
     *
     * 用 Map 而不是 Spring AI 的 ToolContext：手写那条路径不该被迫依赖框架的类。
     * 转换在 NativeToolAgent 里做，只有一行。
     */
    default String run(String input, Map<String, Object> context) {
        return run(input);
    }
}
