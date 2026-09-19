package com.example.agentdemo.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.agentdemo.agent.AgentResult;
import com.example.agentdemo.agent.NativeToolAgent;
import com.example.agentdemo.agent.ReActAgent;
import com.example.agentdemo.agent.ToolRegistry;

/**
 * 三个入口：
 *   /api/agent/react   手写 ReAct 循环
 *   /api/agent/native  Spring AI 原生 tool calling
 *   /api/agent/compare 同一问题跑两边，前端并排对比轨迹（面试演示用这个）
 * 轨迹（steps）一并返回，前端能看到"模型调了哪个工具、传了什么、拿到什么"。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ReActAgent reactAgent;
    private final NativeToolAgent nativeToolAgent;
    private final ToolRegistry registry;

    public AgentController(ReActAgent reactAgent, NativeToolAgent nativeToolAgent, ToolRegistry registry) {
        this.reactAgent = reactAgent;
        this.nativeToolAgent = nativeToolAgent;
        this.registry = registry;
    }

    @GetMapping("/tools")
    public List<ToolInfo> tools() {
        return registry.all().stream()
                .map(t -> new ToolInfo(t.name(), t.description()))
                .toList();
    }

    @GetMapping("/react")
    public AgentResult react(@RequestParam String q) {
        return reactAgent.run(q);
    }

    @GetMapping("/native")
    public AgentResult nativeAgent(@RequestParam String q) {
        return nativeToolAgent.run(q);
    }

    @GetMapping("/compare")
    public CompareView compare(@RequestParam String q) {
        return new CompareView(reactAgent.run(q), nativeToolAgent.run(q));
    }

    public record ToolInfo(String name, String description) {
    }

    public record CompareView(AgentResult react, AgentResult nativeAgent) {
    }
}
