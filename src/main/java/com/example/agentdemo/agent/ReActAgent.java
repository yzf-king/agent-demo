package com.example.agentdemo.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.agentdemo.memory.ChatTurn;

/**
 * 手写 ReAct 循环（不依赖任何 Agent 框架）。
 *
 * 循环长这样：把「问题 + 已经发生过的步骤」拼成一个 prompt 发给模型 →
 * 模型输出 Thought / Action / Action Input → 我解析出工具名，真去执行 →
 * 把工具结果当成 Observation 追加进历史 → 再发一轮，直到模型给出 Final Answer。
 *
 * 为什么先手写一遍：框架把"解析 + 执行 + 回填"全封装了，出问题时你不知道该看哪儿。
 * 手写完再对照 {@link NativeToolAgent}，才知道框架替你做了什么、又替你隐藏了什么。
 *
 * 这套协议源自 ReAct 论文（Reason + Act），模型只输出文本，协议靠 prompt 约定；
 * 所以**解析必须容错**——模型偶尔会自作主张编一个 Observation，见 cutAtSelfObservation。
 */
@Service
public class ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    private static final Pattern FINAL_ANSWER = Pattern.compile(
            "Final Answer\\s*[:：]\\s*(.*)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION = Pattern.compile(
            "Action\\s*[:：]\\s*([A-Za-z_][A-Za-z0-9_\\-]*)");
    private static final Pattern ACTION_INPUT = Pattern.compile(
            "Action Input\\s*[:：]\\s*(.+)", Pattern.DOTALL);
    private static final Pattern THOUGHT = Pattern.compile(
            "Thought\\s*[:：]\\s*(.*?)(?=\\n\\s*(?:Action|Final Answer)\\s*[:：]|$)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** 模型自问自答的开头：出现 "Observation:" 说明它在编工具结果，后面的内容全部丢弃 */
    private static final Pattern SELF_OBSERVATION = Pattern.compile(
            "\\n\\s*Observation\\s*[:：]", Pattern.CASE_INSENSITIVE);

    private final ChatClient chatClient;
    private final ToolRegistry registry;
    private final int maxSteps;

    public ReActAgent(ChatClient chatClient, ToolRegistry registry,
                      @Value("${agent.max-steps:6}") int maxSteps) {
        this.chatClient = chatClient;
        this.registry = registry;
        this.maxSteps = maxSteps;
    }

    public AgentResult run(String question) {
        return run(question, RunContext.empty());
    }

    public AgentResult run(String question, RunContext ctx) {
        long started = System.currentTimeMillis();
        List<AgentStep> steps = new ArrayList<>();
        StringBuilder scratchpad = new StringBuilder();   // 已经发生过的 Thought/Action/Observation

        for (int step = 1; step <= maxSteps; step++) {
            long stepStart = System.currentTimeMillis();
            String raw = chatClient.prompt()
                    .system(systemPrompt(ctx))
                    .user(userPrompt(question, scratchpad, ctx))
                    .call()
                    .content();
            String output = cutAtSelfObservation(raw == null ? "" : raw);
            log.debug("ReAct 第 {} 步原始输出：\n{}", step, output);

            String thought = firstGroup(THOUGHT, output);
            long cost = System.currentTimeMillis() - stepStart;

            Matcher finalMatcher = FINAL_ANSWER.matcher(output);
            if (finalMatcher.find()) {
                // 模型认为信息够了：这一步不再调工具，直接收尾
                steps.add(new AgentStep(step, thought, null, null, null, cost, "model+tool"));
                return new AgentResult(finalMatcher.group(1).trim(), steps, "react", true,
                        System.currentTimeMillis() - started);
            }

            Matcher actionMatcher = ACTION.matcher(output);
            if (!actionMatcher.find()) {
                // 既没有 Action 也没有 Final Answer：模型没按格式来。
                // 兜底当作最终回答收下，但 finished=false，前端会如实标出来（不装作成功）
                log.warn("第 {} 步既无 Action 也无 Final Answer，按自由文本收尾", step);
                steps.add(new AgentStep(step, thought, null, null, output.trim(), cost, "model+tool"));
                return new AgentResult(output.trim(), steps, "react", false,
                        System.currentTimeMillis() - started);
            }

            String action = actionMatcher.group(1).trim();
            String input = firstGroup(ACTION_INPUT, output).replace("\n", " ").trim();
            String observation = registry.execute(action, input, ctx.toolContext());

            steps.add(new AgentStep(step, thought, action, input, observation, cost, "model+tool"));
            // 回填：把模型这轮的原话 + 工具的真实结果接在轨迹后面，下一轮一起发回去
            scratchpad.append(output.trim()).append('\n')
                    .append("Observation: ").append(observation).append("\n\n");
        }

        // 步数用光还没收敛：不硬编答案，把最后一步的观察如实汇报
        return new AgentResult(
                "【未在 " + maxSteps + " 步内得出最终答案】最后一步的观察：" +
                        (steps.isEmpty() ? "无" : steps.get(steps.size() - 1).observation()),
                steps, "react", false, System.currentTimeMillis() - started);
    }

    private String systemPrompt(RunContext ctx) {
        // 长期画像放 system：它对本轮对话是"稳定背景"，不随问题变化，
        // 也避免被历史轮次截断时一起丢掉（这是长期记忆和短期记忆的分工）
        String profile = (ctx.profile() == null || ctx.profile().isBlank())
                ? "" : ctx.profile() + "\n";
        return profile + """
                你是一个会使用工具的助手。你通过"思考 → 行动 → 观察"的循环来回答问题。

                可用工具：
                %s
                输出格式（每一步只输出一组，关键字必须用英文原样写出）：
                Thought: 你的思考，说明为什么要用这个工具
                Action: 工具名，必须是上面清单里的名字
                Action Input: 传给工具的输入，只写一行，不要加引号、不要写解释

                当你已经能从观察里得出结论时，输出：
                Thought: 我已经有足够信息了
                Final Answer: 给用户的最终回答（直接说结论，不要说"根据资料"之类的套话）

                协议规则（这套文本协议特有，框架版用不到）：
                1. 一次只能调用一个工具，调用后就停下等 Observation，**绝对不要自己编造 Observation**
                2. 观察由系统提供，你看到的 Observation 就是工具的返回结果
                3. 信息已经足够时必须用 Final Answer 结束，不要无意义地反复调用工具
                4. 如果工具返回"工具不存在"，说明你写错了名字，请从清单里挑一个正确的

                %s""".formatted(registry.describe(), AgentPrompts.behaviorRules());
    }

    /**
     * 拼用户消息。历史被"压平"成一段带角色标签的文本——这是文本协议的代价，
     * 不是偷懒：ReAct 的整个交互模型就是"我发一段文本、模型回一段文本"，
     * 中间塞不进真正的多角色消息。角色标签（用户：/助手：）能救回来一部分，
     * 但和框架版真正的 message 列表仍然不等价（见 NativeToolAgent）。
     */
    private String userPrompt(String question, StringBuilder scratchpad, RunContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (!ctx.history().isEmpty()) {
            sb.append("本次会话之前的对话（用户可能用「它/那个/那我呢」指代上文，请先看懂再答）：\n");
            for (ChatTurn turn : ctx.history()) {
                sb.append("assistant".equals(turn.role()) ? "助手：" : "用户：")
                        .append(turn.content()).append('\n');
            }
            sb.append("\n---\n\n");
        }
        sb.append("问题：").append(question).append("\n\n");
        if (scratchpad.length() == 0) {
            sb.append("请输出下一步（Thought + Action + Action Input）。");
        } else {
            sb.append("已经发生的步骤：\n").append(scratchpad)
                    .append("请继续输出下一步。如果信息已经足够，用 Final Answer 给出最终回答。");
        }
        return sb.toString();
    }

    /** 截断模型自己编造的 Observation——只保留它编之前的部分 */
    private String cutAtSelfObservation(String output) {
        Matcher m = SELF_OBSERVATION.matcher(output);
        return m.find() ? output.substring(0, m.start()).trim() : output.trim();
    }

    private String firstGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }
}
