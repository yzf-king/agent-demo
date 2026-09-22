package com.example.agentdemo.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Service;

import com.example.agentdemo.memory.ChatTurn;

/**
 * 框架版：同一批工具交给 Spring AI 的原生 tool calling。
 *
 * 和 {@link ReActAgent} 的差别：
 *   手写版——工具清单写在 prompt 里，模型吐文本，正则解析，自己 while 循环，结果自己回填；
 *   框架版——工具定义走 API 的 tools 参数（结构化 schema），模型返回结构化 tool_calls，
 *           执行与回填都在框架内部完成，我们只拿到最终回答。
 *
 * 那"轨迹"从哪来？框架不告诉你中间发生了什么，但**工具回调是我们自己的代码**，
 * 在回调里记一笔就有了（见下面的匿名 ToolCallback）。这也是实话实说：
 * 框架省掉了协议解析，代价是中间过程默认不可见，要看就得自己埋点。
 *
 * 待验证：框架内部那个"模型要工具 → 执行 → 再问模型"的循环，步数上限是多少、
 * 超限怎么办，我没测过——注释里标出来，别当成已知结论讲。
 */
@Service
public class NativeToolAgent {

    private final ChatClient chatClient;
    private final ToolRegistry registry;

    public NativeToolAgent(ChatClient chatClient, ToolRegistry registry) {
        this.chatClient = chatClient;
        this.registry = registry;
    }

    public AgentResult run(String question) {
        return run(question, RunContext.empty());
    }

    public AgentResult run(String question, RunContext ctx) {
        long started = System.currentTimeMillis();
        List<AgentStep> steps = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger counter = new AtomicInteger();

        ToolCallback[] callbacks = registry.all().stream()
                .map(tool -> recordingCallback(tool, steps, counter))
                .toArray(ToolCallback[]::new);

        String answer = chatClient.prompt()
                // 工具清单不用写进 prompt：框架会把它作为 API 的 tools 参数发过去。
                // 但"行为约束"必须显式带上——框架只负责把工具给模型，管不了它编不编（见 AgentPrompts 注释）
                .system(profileOrRules(ctx))
                // 历史走真正的消息列表，不是拼进一段文本：
                // 框架这边能保留 user/assistant 的角色边界，模型更容易分清哪句是谁说的
                .messages(historyMessages(ctx, question))
                .toolCallbacks(callbacks)
                // userId 从这进工具（模型看不到、也编不出来），对应 ToolContext 机制
                .toolContext(ctx.toolContext())
                .call()
                .content();

        return new AgentResult(answer == null ? "" : answer.trim(), steps, "native", true,
                System.currentTimeMillis() - started);
    }

    private String profileOrRules(RunContext ctx) {
        String profile = (ctx.profile() == null || ctx.profile().isBlank()) ? "" : ctx.profile() + "\n";
        return profile + AgentPrompts.behaviorRules();
    }

    private List<Message> historyMessages(RunContext ctx, String question) {
        List<Message> messages = new ArrayList<>();
        for (ChatTurn turn : ctx.history()) {
            messages.add("assistant".equals(turn.role())
                    ? new AssistantMessage(turn.content())
                    : new UserMessage(turn.content()));
        }
        messages.add(new UserMessage(question));
        return messages;
    }

    /** 用 FunctionToolCallback 把 Tool 包成框架要求的回调，外面再套一层"记轨迹"的装饰 */
    private ToolCallback recordingCallback(Tool tool, List<AgentStep> steps, AtomicInteger counter) {
        // 工具定义这段和 MCP 框架版共用（ToolCallbacks.of）——两条路的工具必须逐字相同，
        // 否则对比实验测出来的差异不知道是协议造成的还是这里写的不同
        FunctionToolCallback<ToolInput, String> inner = ToolCallbacks.of(tool, registry, Map.of());

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return inner.getToolDefinition();
            }

            // ToolCallback 里 call(String) 是抽象方法，call(String, ToolContext) 是 default（会转发到这里），
            // 所以只重写单参版本即可覆盖两条调用路径（1.1.8 实测，javap 确认）
            @Override
            public String call(String toolInput) {
                long stepStart = System.currentTimeMillis();
                String observation = inner.call(toolInput);
                steps.add(new AgentStep(
                        counter.incrementAndGet(),
                        null,   // 框架版拿不到模型这一步的自然语言思考，只能记工具调用
                        tool.name(),
                        toolInput,
                        observation,
                        System.currentTimeMillis() - stepStart,
                        // 只测得到工具执行：模型那几轮调用都在框架内部，这里埋不到点
                        "tool-only"));
                return observation;
            }
        };
    }
}
