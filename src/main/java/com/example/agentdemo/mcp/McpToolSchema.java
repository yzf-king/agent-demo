package com.example.agentdemo.mcp;

import com.example.agentdemo.agent.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 把本项目内部的 {@link Tool} 翻译成 MCP 的 tool 定义。
 *
 * 翻译本身很短，短到值得停下来看一眼：**难的地方从来不是这段代码，
 * 而是"两个协议对参数形状的要求不一样，中间得有人做适配"。**
 *
 *   tool.run(String)                        ← 我们的内部约定：进字符串、出字符串
 *   {"name": "input", "type": "string"}     ← MCP 要求 arguments 是个对象，且 inputSchema.type 必须是 "object"
 *
 * 于是套一层壳：MCP 侧看到的是 {"input": "..."}，我们取 arguments.input 再交给 tool.run。
 * 这和 W3 给 OpenAI tools 协议套 {@link com.example.agentdemo.agent.ToolInput} 是**完全同一件事**，
 * 两处评论可以对照着看——不同协议、同一个约束（要按名字取参数，参数就得是对象）。
 */
final class McpToolSchema {

    private McpToolSchema() {
    }

    static ObjectNode toMcpTool(ObjectMapper mapper, Tool tool) {
        // 属性描述直接用工具自己的 description：
        // 客户端会把它喂给模型，写得好不好仍然直接决定模型会不会用、用得对不对（W3 的结论在 MCP 下同样成立）
        ObjectNode inputProperty = mapper.createObjectNode();
        inputProperty.put("type", "string");
        inputProperty.put("description", tool.description());

        ObjectNode properties = mapper.createObjectNode();
        properties.set("input", inputProperty);

        ObjectNode inputSchema = mapper.createObjectNode();
        inputSchema.put("type", "object");          // 规范里这一项是 const "object"，别写成别的
        inputSchema.set("properties", properties);
        inputSchema.putArray("required").add("input");

        ObjectNode node = mapper.createObjectNode();
        node.put("name", tool.name());
        node.put("description", tool.description());
        node.set("inputSchema", inputSchema);
        return node;
    }
}
