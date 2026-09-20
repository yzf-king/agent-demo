package com.example.agentdemo.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工具注册表：Agent 只认名字，这里负责"名字 → 实现"的查找和异常兜底。
 *
 * 兜底很关键：工具体抛异常（网络断了、模型给了个不存在的城市），
 * 不能让整个 Agent 崩掉——把错误当成 Observation 回给模型，它有机会自己改主意。
 * 这也顺带防了"模型瞎编工具名"：查不到就返回可用清单。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> toolList) {
        for (Tool t : toolList) {
            tools.put(t.name(), t);
        }
    }

    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    /** 拼成给模型看的工具清单（写进 system prompt） */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Tool t : tools.values()) {
            sb.append("- ").append(t.name()).append(": ").append(t.description()).append('\n');
        }
        return sb.toString();
    }

    /** 执行工具。永不抛异常：失败信息也是给模型的 Observation */
    public String execute(String name, String input) {
        return execute(name, input, Map.of());
    }

    /**
     * 带上下文执行。context 里放的是"模型拿不到、也编不出来"的运行时信息，
     * 比如当前 userId——所以不能进 prompt，只能从这里递给工具。
     */
    public String execute(String name, String input, Map<String, Object> context) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "【工具不存在】可用工具：" + String.join("、", tools.keySet());
        }
        try {
            String out = tool.run(input, context == null ? Map.of() : context);
            return (out == null || out.isBlank()) ? "【工具返回空】" : out;
        } catch (Exception e) {
            log.warn("工具 {} 执行失败：{}", name, e.getMessage());
            return "【工具执行失败】" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
