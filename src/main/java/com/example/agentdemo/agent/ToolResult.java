package com.example.agentdemo.agent;

/**
 * 工具执行结果：文本 + 是否失败。
 *
 * 为什么不是光返回 String：错误与否是**结构化信息**，不该藏在文本里靠猜。
 * 这不是凭空想出来的——写 MCP server 时我图省事，用"返回值以「【」开头就当失败"来判断，
 * 一跑就翻车：知识检索的正常结果本来就长这样（`【片段 1｜相似度 0.636】…`），
 * 于是检索成功被标成了 isError:true。
 *
 * 模型看这个标志、MCP 客户端也看这个标志（协议里的 CallToolResult.isError）：
 * 标错了，客户端就会把正常结果当失败处理；标漏了，模型看不到错误、没法自我纠正。
 * 所以它必须是个字段，不能是"看起来像"。
 */
public record ToolResult(String text, boolean error) {

    public static ToolResult ok(String text) {
        return new ToolResult(text == null || text.isBlank() ? "【工具返回空】" : text, false);
    }

    public static ToolResult fail(String text) {
        return new ToolResult(text, true);
    }
}
