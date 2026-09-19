package com.example.agentdemo.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Service;

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
        long started = System.currentTimeMillis();
        List<AgentStep> steps = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger counter = new AtomicInteger();

        ToolCallback[] callbacks = registry.all().stream()
                .map(tool -> recordingCallback(tool, steps, counter))
                .toArray(ToolCallback[]::new);

        String answer = chatClient.prompt()
                .user(question)
                .toolCallbacks(callbacks)
                .call()
                .content();

        return new AgentResult(answer == null ? "" : answer.trim(), steps, "native", true,
                System.currentTimeMillis() - started);
    }

    /** 用 FunctionToolCallback 把 Tool 包成框架要求的回调，外面再套一层"记轨迹"的装饰 */
    private ToolCallback recordingCallback(Tool tool, List<AgentStep> steps, AtomicInteger counter) {
        // 参数必须包成对象（见 ToolInput 的注释：直接给 String.class 会被 DeepSeek 判 400）
        Function<ToolInput, String> fn = in -> tool.run(in == null || in.input() == null ? "" : in.input());
        FunctionToolCallback<ToolInput, String> inner = FunctionToolCallback
                .builder(tool.name(), fn)
                .description(tool.description() + "（参数放进 input 字段）")
                .inputType(ToolInput.class)
                .build();

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
