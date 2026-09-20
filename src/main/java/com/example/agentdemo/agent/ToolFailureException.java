package com.example.agentdemo.agent;

/**
 * 工具的"软失败"：不是因为代码出 bug 抛的异常，而是这次调用本身没成功
 * （参数格式不对、没权限、下游返回空、画像已达上限……）。
 *
 * 为什么要区分：这两类失败给用户/模型的信息不一样。
 *   - 软失败：把原因说清楚，模型能自己改一次再试（"格式应该写成 key=value"）
 *   - 意外异常：只报类型和消息，因为连我们自己都不知道怎么了
 * 但两者最终都是 ToolResult(error=true)——结果一样，措辞不同。
 *
 * 有了它，工具就不用再靠"返回一句以「【」开头的话"来表达失败了，
 * 那种字符串约定会被正常返回值误伤（见 ToolResult 的注释）。
 */
public class ToolFailureException extends RuntimeException {

    public ToolFailureException(String message) {
        super(message);
    }
}
