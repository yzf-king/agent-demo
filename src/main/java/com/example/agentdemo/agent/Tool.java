package com.example.agentdemo.agent;

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
}
