package com.example.agentdemo.agent;

/**
 * 框架版工具的统一入参壳子。
 *
 * 为什么需要这么一层：FunctionToolCallback 的 inputType 直接给 String.class，
 * 生成的 schema 是 {"type":"string"}，而 OpenAI 兼容的 tools 协议要求参数顶层必须是 object，
 * DeepSeek 会直接返回 400：schema must be a JSON Schema of 'type: "object"', got 'type: "string"'。
 * 包成 record 后 schema 变成 {"type":"object","properties":{"input":{"type":"string"}}}，模型按
 * {"input":"(1200*0.8)+50"} 调，框架再反序列化成这个记录。
 *
 * 对比：手写 ReAct 完全没有这个约束——Action Input 本来就是一行自由文本，
 * 解析出来的字符串直接喂给工具。框架换来的是结构化与类型安全，代价是你要按它的形状组织参数。
 */
public record ToolInput(String input) {
}
