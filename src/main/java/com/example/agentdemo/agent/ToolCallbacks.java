package com.example.agentdemo.agent;

import java.util.Map;
import java.util.function.BiFunction;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * 把内部 {@link Tool} 包成框架要的 {@link FunctionToolCallback}。
 *
 * 为什么单独抽出来：{@link NativeToolAgent}（走 OpenAI tools 协议）和
 * {@code mcp.McpFrameworkServer}（走 MCP）是同一个东西的两条路，
 * 做对比实验的前提是**除了"谁来搬这个工具"以外，其余必须逐字相同**。
 * 这段要是各写一份，两边稍有出入，测出来的差异就不知道是协议造成的还是笔误造成的。
 *
 * 这个教训不是假设：W4 比手写 ReAct 和框架版时，就出过一次
 * "两条路用了两套不同的行为约束"——差异大到差点被我当成模型不稳定（见 AgentPrompts 注释）。
 *
 * 顺带一个结论：**框架这一层并不省事**。{"input": string} 这个壳、
 * description 怎么拼、参数对象怎么包，全都得自己做，
 * 而且 MCP 和 OpenAI tools 两个协议要求的是同一个形状（见 {@link ToolInput} 注释）。
 * 框架真正替你做的是传输和协议状态机，不是工具定义。
 *
 * === 两条路在这里分叉：失败该怎么表达（都是 javap 读字节码读出来的，不是猜的）===
 *
 * 手写 MCP server 不需要这段适配：它自己造 CallToolResult，
 * 把 {@link ToolResult#error()} 填进 isError 就完事。
 * 框架版没这个自由——{@code McpToolUtils} 里 isError 是**写死的常量**：
 * 成功路径压的是 iconst_0（false），只有 catch (Exception) 那条分支才是 iconst_1（true）。
 * 也就是说：**在框架里，工具失败唯一的表达方式就是抛异常**。
 *
 * 所以 MCP 用 {@link #ofForMcp}（失败即抛），Agent 用 {@link #of}（失败当文本回给模型）。
 * 同一个 {@link ToolResult}，两个消费方要两种翻译——这层适配框架不会替你做。
 * 把它删掉会怎样？我删过：框架版一开始直接用了 {@code of}，
 * 结果工具明明失败了，报文里却是 {@code "isError": false}——正是手写版修过的 Bug C，
 * 从另一个门又进来了。
 */
public final class ToolCallbacks {

    /**
     * 默认的 {@code DefaultToolCallResultConverter} 会把返回值 JSON 化，**连 String 也加引号**：
     * 工具返回 {@code 1+1 = 2}，模型/MCP 客户端收到的是 {@code "1+1 = 2"}——外层被套了一层
     * JSON 字符串字面量（字节码里那个 {@code JsonParser.toJson(result)} 是无条件的）。
     * 我们的工具一律返回纯文本，所以这里原样透传。
     */
    private static final ToolCallResultConverter PLAIN_TEXT =
            (result, returnType) -> result == null ? "" : result.toString();

    private ToolCallbacks() {
    }

    /**
     * Agent 版：工具失败也返回文本，让模型看见原因、有机会自己改一次。
     *
     * @param fixedContext 没有框架级上下文时用的固定上下文。
     *                     框架给了 {@link ToolContext} 就优先用它——
     *                     Spring AI 在响应 MCP 调用时会把 exchange 塞进 ToolContext 的
     *                     {@code "exchange"} 键（{@code McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY}），
     *                     走这条路能拿到 sessionId / clientInfo，但**仍然拿不到"当前用户是谁"**。
     */
    public static FunctionToolCallback<ToolInput, String> of(Tool tool, ToolRegistry registry,
                                                             Map<String, Object> fixedContext) {
        return build(tool, registry, fixedContext, false);
    }

    /**
     * MCP 版：工具失败就抛异常，框架的 catch 分支会把它翻成 {@code isError: true}
     * 并把 {@code getMessage()} 原文放进 content——所以我们给模型看的措辞一条都不丢。
     *
     * 这里抛的异常类型其实不重要（框架是 catch Exception），
     * 但仍然用 {@link ToolFailureException}：语义准确，而且万一以后框架改成只认某类异常，
     * 我们这边已经是对的那个。
     */
    public static FunctionToolCallback<ToolInput, String> ofForMcp(Tool tool, ToolRegistry registry,
                                                                   Map<String, Object> fixedContext) {
        return build(tool, registry, fixedContext, true);
    }

    private static FunctionToolCallback<ToolInput, String> build(Tool tool, ToolRegistry registry,
                                                                 Map<String, Object> fixedContext,
                                                                 boolean throwOnError) {
        BiFunction<ToolInput, ToolContext, String> fn = (in, toolCtx) -> {
            String input = in == null || in.input() == null ? "" : in.input();
            Map<String, Object> fromFramework = toolCtx == null ? null : toolCtx.getContext();
            Map<String, Object> ctx = (fromFramework != null && !fromFramework.isEmpty())
                    ? fromFramework : fixedContext;
            // 走 registry 而不是直接 tool.run：两条路的异常兜底行为必须一致
            ToolResult result = registry.execute(tool.name(), input, ctx);
            if (throwOnError && result.error()) {
                throw new ToolFailureException(result.text());
            }
            return result.text();
        };
        return FunctionToolCallback.builder(tool.name(), fn)
                .description(tool.description() + "（参数放进 input 字段）")
                .inputType(ToolInput.class)
                .toolCallResultConverter(PLAIN_TEXT)
                .build();
    }
}
