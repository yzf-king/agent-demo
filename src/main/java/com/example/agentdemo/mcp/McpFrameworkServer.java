package com.example.agentdemo.mcp;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.example.agentdemo.agent.ToolCallbacks;
import com.example.agentdemo.agent.ToolRegistry;
import com.example.agentdemo.memory.UserProfileStore;
import com.example.agentdemo.tools.CalculatorTool;
import com.example.agentdemo.tools.CurrentTimeTool;
import com.example.agentdemo.tools.KnowledgeSearchTool;
import com.example.agentdemo.tools.MemorySaveTool;
import com.example.agentdemo.tools.WeatherTool;

/**
 * 框架版 MCP Server（Spring AI 的 {@code spring-ai-starter-mcp-server}，stdio 传输）。
 *
 * 和手写的 {@link McpStdioServer} 是**同一个东西的两条路**，工具集完全相同，
 * 目的是回答"框架替我省了什么"。
 *
 * 下面每一条都有实测数据撑腰（scripts/mcp/ 下那几个脚本），
 * **而且第一条我一开始写反了**——所以特别标出来，免得又照着错的讲。
 *
 * === 框架替我做了什么 ===
 * 1. **JSON-RPC 状态机**：initialize / 版本协商 / notifications/initialized /
 *    tools/list / tools/call / 错误码，全都不用写。手写版那 250 行里大部分是这个。
 *    附带效果：状态机也**挡住了不合规的客户端**（手写版没有状态机，所以它"宽容"地
 *    接受任何时序——宽容在这里不是优点，是没守住规范）。
 * 2. **分帧与 flush**：一行一条 JSON、什么时候 flush，这些不用操心了。
 * 3. **工具 schema 的生成**：从 ToolCallback 的 inputType 反射出 JSON Schema，
 *    手写版对应的是 McpToolSchema 那个类。
 *
 * === 框架没替我做什么（坑都在这，全是实测） ===
 * 1. **stdout 纪律——这条我写反过**。我原本在注释里断言"框架自己管住了 stdout"，
 *    逻辑是"手写版第一件事是把 System.out 重定向到 stderr，框架版不用写这行"。
 *    跑了一次就翻车：**stdout 17 行里 15 行是日志/banner，只有 2 行是协议报文**，
 *    stderr 0 行。框架不但不管，它还让 Spring Boot 的 ConsoleAppender 正对着协议通道输出。
 *    修法是 logback-spring.xml 把日志挪到 stderr（见该文件的注释）。
 *    **教训：手写版需要你写的那行代码，框架版不会因为你没用它就不需要——
 *    它只是换了个地方需要你。**
 * 2. **工具定义本身**：{"input": string} 这个壳还是要自己套，
 *    因为 MCP 和 OpenAI tools 两个协议都要求参数顶层是 object
 *    （见 {@link com.example.agentdemo.agent.ToolInput} 的注释）。
 *    两条路共用 {@link ToolCallbacks#of}——这段一个字都没被框架省掉。
 * 3. **失败语义的翻译**：框架里 isError 是写死的（成功 false、catch 了才 true），
 *    所以"工具失败"只能用抛异常表达——见 {@link ToolCallbacks#ofForMcp} 的注释。
 *    我一开始直接复用了给 Agent 用的 {@code of}，结果工具失败了报文里却是
 *    isError:false——手写版修过的 Bug C 从另一个门又进来了。
 * 4. **返回值原样性**：框架默认的 result converter 会把 String 也 JSON 化，
 *    {@code 1+1 = 2} 变成 {@code "1+1 = 2"}（带引号）进模型上下文。
 *    也算"框架悄悄改了你的数据"。
 *
 * === 手写版能看见、框架版看不见的一件事 ===
 * 手写版我在 tools/call 那行打了日志（耗时、error 标志、返回多少字符）。
 * 框架版这一段在它内部，**默认没有任何中间过程可见**——要看就得自己给
 * ToolCallback 套一层装饰（{@code NativeToolAgent} 里那么干过）。
 * 和第 3、4 条是同一个规律：框架替你搬砖，代价是你不知道砖怎么摆的。
 *
 * === 框架多出来的东西：并发，以及随之而来的竞态 ===
 * 手写版是一条线程"读一行 → 处理 → 写一行"，结构上不可能有并发问题。
 * 框架版把请求丢到 boundedElastic 线程池上并发处理，于是：
 * **多条请求连着发的时候，响应会随机丢一两条**，stderr 里写着
 * {@code Failed to enqueue message} + {@code Operator called default onErrorDropped}
 * ——多个线程同时往同一个 sink 里写响应，失败的那个错误被丢掉了，调用方永远不知道。
 * 严格一问一答的客户端（Claude Desktop、Inspector 这些）不会触发，
 * 所以这不是"框架不能用"，但它是**手写版靠简单换来的、框架版用并发换掉的一个性质**。
 *
 * === 和 Web 应用（8083）的关系 ===
 * 这个类是**另一个启动入口**，不是 8083 那个应用的一部分。
 * 用 WebApplicationType.NONE 起，不监听端口，只吃 stdin 写 stdout。
 * 下面 @Import 是故意的：只把工具装进来，不扫整个包——
 * 否则会把 ReActAgent / NativeToolAgent 一起拉起来，它们要 ChatClient，
 * 而一个 MCP 工具服务器根本不需要大模型（这一点本身也值得注意：
 * **MCP server 侧是完全无模型依赖的**，模型在客户端那边）。
 */
@SpringBootApplication
@Import({CalculatorTool.class, CurrentTimeTool.class, WeatherTool.class,
        KnowledgeSearchTool.class, MemorySaveTool.class, UserProfileStore.class,
        ToolRegistry.class})
public class McpFrameworkServer {

    public static void main(String[] args) {
        new SpringApplicationBuilder(McpFrameworkServer.class)
                .web(WebApplicationType.NONE)
                // 激活 mcp-stdio profile：logback-spring.xml 里靠它把日志从 stdout 挪到 stderr。
                // 不用这行的话，框架的启动日志会和协议报文挤在 stdout 上，客户端解析失败——
                // 框架自己不管这件事，得我们管（见 logback-spring.xml 的注释和实测数据）
                .profiles("mcp-stdio")
                .properties(
                        // 这三条就是"把它变成 stdio MCP server"的全部开关
                        "spring.ai.mcp.server.stdio=true",
                        "spring.ai.mcp.server.name=agent-demo-tools-framework",
                        "spring.ai.mcp.server.version=0.1.0",
                        "spring.ai.mcp.server.instructions=这个服务器提供企业助理的工具：知识库检索（公司制度问答）、计算器、当前时间、天气。涉及公司制度的问题请先用 knowledge_search 检索，只依据返回的片段回答。",
                        // banner 关掉；就算框架已经管住了 stdout，少一样东西总是好的
                        "spring.main.banner-mode=off")
                .run(args);
    }

    /**
     * 框架靠这个 bean 发现工具：它会把所有 {@link ToolCallback} 收集起来，
     * 用 {@code McpToolUtils} 转成 MCP 的 tool 定义。
     *
     * 只写这一行就接完了——手写版对应的代码是 {@code McpToolSchema} 那个类，
     * 加上 McpStdioServer 里的 tools/list 分支。
     */
    @Bean
    List<ToolCallback> mcpTools(ToolRegistry registry) {
        return registry.all().stream()
                .map(tool -> (ToolCallback) ToolCallbacks.ofForMcp(tool, registry, java.util.Map.of()))
                .toList();
    }
}
