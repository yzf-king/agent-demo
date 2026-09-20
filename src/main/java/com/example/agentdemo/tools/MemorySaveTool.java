package com.example.agentdemo.tools;

import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.agentdemo.agent.Tool;
import com.example.agentdemo.memory.UserProfileStore;

/**
 * 让 Agent 把"关于用户的稳定事实"写进长期记忆。
 *
 * 这是本项目的第一个**需要上下文**的工具：它必须知道当前是谁，
 * 而这个信息不能靠模型传——模型不知道 userId，你让它传它只会编一个，
 * 甚至可能编成别人的（用户说"记住我是技术部的"，模型填 userId=技术部）。
 * 所以 userId 从 {@link com.example.agentdemo.agent.ToolRegistry#execute} 的 context 进来。
 *
 * description 里反复强调"长期、稳定"：实测如果只说"记住信息"，模型会把
 * 这次问的问题也记下来（"用户问过远程办公"），画像很快被一次性信息塞满。
 * 工具说明写不准 → 模型用不对，这条比框架选型重要得多。
 */
@Component
public class MemorySaveTool implements Tool {

    /** 上下文里 userId 的键名，Web 层和工具共用这一个常量 */
    public static final String CTX_USER_ID = "userId";

    private final UserProfileStore profiles;

    public MemorySaveTool(UserProfileStore profiles) {
        this.profiles = profiles;
    }

    @Override
    public String name() {
        return "memory_save";
    }

    @Override
    public String description() {
        return "记住关于当前用户的**长期稳定**事实，例如：所在部门、职位、入职时间、长期偏好、称呼。"
                + "输入格式：`key=value`（如 `部门=技术部`）。一次可以传多组，用逗号隔开（如 `姓名=张三,部门=技术部`）。"
                + "只记长期不变的；用户这次问的问题、一次性的需求不要记。同名 key 会覆盖旧值。";
    }

    /** 不该被调用：这个工具必须带上下文，见 run(String, Map) */
    @Override
    public String run(String input) {
        return "【工具执行失败】缺少用户上下文，无法确定要记住谁";
    }

    @Override
    public String run(String input, Map<String, Object> context) {
        String userId = String.valueOf(context.getOrDefault(CTX_USER_ID, ""));

        String raw = input == null ? "" : input.trim();
        if (raw.isEmpty()) {
            return "【参数格式不对】需要 `key=value`，例如 `部门=技术部`。你传的是空字符串。";
        }

        // 模型经常把多组信息挤在一行（实测 `姓名=王五, 部门=市场部` 被当成一个键，两条事实并成一个）。
        // 光靠 description 里写"一次一条"是概率性的，解析层兜住才确定。
        String[] parts = PAIR_SPLIT.split(raw);
        StringBuilder ok = new StringBuilder();
        for (String part : parts) {
            String segment = part.trim();
            if (segment.isEmpty()) {
                continue;
            }
            int sep = firstSeparator(segment);
            if (sep <= 0 || sep == segment.length() - 1) {
                return "【参数格式不对】每一段都要是 `key=value`，收到的是「" + segment
                        + "」。整串原文：" + raw;
            }
            String key = segment.substring(0, sep).trim();
            String value = segment.substring(sep + 1).trim();
            String error = profiles.put(userId, key, value);
            if (error != null) {
                return "【未能记住】" + error;
            }
            ok.append(key).append(" = ").append(value).append("；");
        }
        return ok.isEmpty()
                ? "【参数格式不对】没解析出任何 key=value，原文：" + raw
                : "已记住：" + ok;
    }

    /**
     * 只在"逗号/分号后面确实跟着下一个 key="时才切分，避免把值里的逗号也当分隔符
     * （`地址=杭州市,西湖区` 不应该被切成两段）。
     * 这是个启发式，不是完备解析——真要严谨就该让模型走结构化 JSON 参数，
     * 但手写 ReAct 那条路径只有纯文本，只能在这层做容错。局限写在这，别当成万能。
     */
    private static final Pattern PAIR_SPLIT = Pattern.compile("[,，;；\\n]\\s*(?=[^,，;；=\\n]{1,12}[=:：])");

    private int firstSeparator(String s) {
        int best = -1;
        for (int i : new int[]{s.indexOf('='), s.indexOf(':'), s.indexOf('：')}) {
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }
}
