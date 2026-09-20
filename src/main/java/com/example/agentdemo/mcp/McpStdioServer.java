package com.example.agentdemo.mcp;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.example.agentdemo.agent.Tool;
import com.example.agentdemo.agent.ToolRegistry;
import com.example.agentdemo.agent.ToolResult;
import com.example.agentdemo.memory.UserProfileStore;
import com.example.agentdemo.tools.CalculatorTool;
import com.example.agentdemo.tools.CurrentTimeTool;
import com.example.agentdemo.tools.KnowledgeSearchTool;
import com.example.agentdemo.tools.MemorySaveTool;
import com.example.agentdemo.tools.WeatherTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 手写的 MCP Server（stdio 传输），不依赖 MCP SDK。
 *
 * 为什么手写：和 W3 手写 ReAct 是同一个理由——先看清协议本身长什么样。
 * 手写一遍之后你会发现 MCP 并不神秘：**就是一个跑在 stdin/stdout 上的 JSON-RPC 2.0 服务**，
 * 方法名一共用到四个（initialize / notifications/initialized / tools/list / tools/call）。
 *
 * === 协议里几个必须踩准的点（都来自 2025-06-18 规范原文，不是我猜的）===
 *
 * 1. **分帧是"一行一条 JSON"，不是 Content-Length 头**（那是 LSP 的做法）。
 *    规范原文："Messages are delimited by newlines, and MUST NOT contain embedded newlines."
 *    所以写入时必须序列化成单行——Jackson 默认不 pretty-print，正好符合；
 *    工具返回值里的换行会被转义成 \n，也不会破坏分帧。
 *
 * 2. **stdout 是协议通道，一个字节都不能污染**。规范原文：
 *    "The server MUST NOT write anything to its stdout that is not a valid MCP message."
 *    这一条最容易翻车：Spring 的 banner、logback 的日志默认全走 stdout，
 *    只要打一行日志出去，客户端就解析失败，而且是那种"看不出来哪错了"的失败。
 *    下面 main 里第一件事就是把 System.out 换成 stderr——见 captureProtocolStream()。
 *
 * 3. **工具执行失败 ≠ 协议错误**。规范明确要求：工具自己出错（比如参数不对、下游服务挂了）
 *    要用结果里的 isError:true 返回，**不能**用 JSON-RPC 的 error 字段。
 *    原因规范也写了：否则模型看不到错误、也就没法自我纠正。这和我们 ToolRegistry 里
 *    "异常兜底成 Observation" 是同一个设计思想——两边不谋而合。
 *    只有"工具根本不存在""服务器不支持工具"这类才走 JSON-RPC error。
 *
 * 4. **inputSchema 顶层必须是 object**。规范里 Tool.inputSchema.type 是 const "object"。
 *    眼熟吗——W3 接 OpenAI tools 协议时踩的就是这个坑（见 ToolInput 的注释）。
 *    两个协议都这样，原因也一样：要按名字取参数，参数就得是个对象。
 *
 * === 和 W3 的 Tool 接口对上了吗 ===
 * 对上了，而且**一个工具实现都没改**：{@link Tool} 进字符串、出字符串，
 * 这里在 MCP 侧给它套一个 {"input": string} 的壳。同一批工具现在有三种暴露方式：
 *   手写 ReAct（文本协议） / Spring AI tool calling（OpenAI tools 协议） / MCP（JSON-RPC）。
 */
public final class McpStdioServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 本服务器声明支持哪些协议版本；客户端来哪个、只要在列表里就顺着它 */
    private static final String PREFERRED_VERSION = "2025-06-18";
    private static final List<String> SUPPORTED_VERSIONS =
            List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    private static final String SERVER_NAME = "agent-demo-tools";
    private static final String SERVER_VERSION = "0.1.0";

    private static final String INSTRUCTIONS = """
            这个服务器提供企业助理的工具：知识库检索（公司制度问答）、计算器、当前时间、天气。
            涉及公司制度的问题请先用 knowledge_search 检索，只依据返回的片段回答。
            """;

    /** JSON-RPC / MCP 用到的错误码 */
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;
    /** 非规范强制，但 SDK 实现里常见的约定：还没 initialize 就来调业务方法 */
    private static final int SERVER_NOT_INITIALIZED = -32002;

    private final ToolRegistry registry;
    private boolean initialized = false;

    private McpStdioServer(ToolRegistry registry) {
        this.registry = registry;
    }

    public static void main(String[] args) throws IOException {
        PrintStream protocolOut = captureProtocolStream();
        new McpStdioServer(buildRegistry()).run(protocolOut);
    }

    /**
     * 把真正的 stdout 留下来当协议通道，然后把 System.out 换到 stderr。
     * 这一步做两件事，缺一不可：
     *
     * 1. **协议通道要干净**：做完之后任何 logback / System.out.println 都只出现在 stderr，
     *    客户端能拿去打日志。不做的话，一句 "INFO ..." 就足以让客户端解析失败。
     *
     * 2. **必须显式指定 UTF-8**：这是实测踩出来的第二个坑，而且很隐蔽——
     *    JSON-RPC 的报文结构完全正确，客户端也收得到，但中文全是乱码。
     *    原因：`System.out` 这个 PrintStream 用**平台默认编码**（Windows 上是 GBK）编码字符，
     *    而 MCP 走的是 UTF-8。英文照样正常，所以不测中文就发现不了。
     *    显式 new 一个 UTF-8 的 PrintStream 包住 stdout 就解决了——
     *    别指望 `-Dfile.encoding=UTF-8`（JDK 18+ 只管 API 默认编码，管不到 System.out）。
     *
     * 日志也一样走 UTF-8：stderr 里中文乱码时，排查问题会多绕一圈。
     */
    private static PrintStream captureProtocolStream() {
        PrintStream realStdout = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream utf8Stderr = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        System.setOut(utf8Stderr);
        // System.err 也得换：只换 System.out 的话，我自己 println 到 stderr 的日志仍然是平台编码（乱码）。
        // 有意思的是 logback 反而不用管——它的 ConsoleAppender 默认目标就是 System.out，
        // 上面那行重定向顺手把它也带到 stderr 了，所以框架日志是好的、我自己的日志是坏的。
        System.setErr(utf8Stderr);
        return realStdout;
    }

    /**
     * 直接 new 出工具，不走 Spring 容器——
     * 一是 stdio server 启动要快（客户端每次连接都会拉起进程），
     * 二是少一层容器就少一处往 stdout 写东西的可能。
     * 工具类本身没有任何 Spring 依赖，所以能这么用。
     *
     * 注意：这里的 UserProfileStore 是这个进程自己的内存，和 Web 服务（8083）那个**不共享**。
     * 这不是 bug，是"内存态记忆不跨实例"的真实体现——见 README 的已知局限。
     */
    private static ToolRegistry buildRegistry() {
        String baseUrl = System.getenv().getOrDefault("AGENT_KNOWLEDGE_BASE_URL", "http://localhost:8081");
        return new ToolRegistry(List.of(
                new CalculatorTool(),
                new CurrentTimeTool(),
                new WeatherTool(),
                new KnowledgeSearchTool(baseUrl),
                new MemorySaveTool(new UserProfileStore(20, 200))));
    }

    private void run(PrintStream out) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            ObjectNode response = handleLine(line);
            if (response != null) {
                // 一行一条：用 writeValueAsString（不是 writerWithDefaultPrettyPrinter），
                // 否则会输出多行，破坏分帧
                out.println(MAPPER.writeValueAsString(response));
                out.flush();   // 必须立刻 flush：stdout 被缓冲住的话客户端会一直等
            }
        }
        // stdin 被关闭 = 客户端断开，正常退出（退出码 0，不是错误）
    }

    /** @return 要回给客户端的响应；通知（没有 id）返回 null */
    private ObjectNode handleLine(String line) {
        JsonNode req;
        try {
            req = MAPPER.readTree(line);
        } catch (Exception e) {
            // 连 JSON 都不是：没有 id 可回，按规范 id 用 null
            return errorResponse(MAPPER.nullNode(), PARSE_ERROR, "解析失败：" + e.getMessage());
        }

        JsonNode id = req.get("id");
        String method = req.path("method").asText("");

        // 没有 id = 通知（notification），规范规定**不能回响应**
        if (id == null || id.isNull()) {
            if ("notifications/initialized".equals(method)) {
                initialized = true;   // 客户端确认握手完成，之后才开始真正用工具
            }
            // notifications/cancelled、notifications/progress 等一律忽略即可，不用回
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> initialize(id, req.path("params"));
                case "ping" -> result(id, MAPPER.createObjectNode());   // 规范：ping 返回空结果
                case "tools/list" -> {
                    requireInitialized(id);
                    yield toolsList(id);
                }
                case "tools/call" -> {
                    requireInitialized(id);
                    yield toolsCall(id, req.path("params"));
                }
                default -> errorResponse(id, METHOD_NOT_FOUND, "不支持的方法：" + method);
            };
        } catch (NotInitializedException e) {
            return errorResponse(id, SERVER_NOT_INITIALIZED, e.getMessage());
        } catch (Exception e) {
            return errorResponse(id, INTERNAL_ERROR, e.toString());
        }
    }

    private ObjectNode initialize(JsonNode id, JsonNode params) {
        JsonNode clientVersion = params.path("protocolVersion");
        String requested = clientVersion.isMissingNode() ? "" : clientVersion.asText();
        // 版本协商：客户端支持的我支持，就用它的；不支持则回我偏好的那个，
        // 由客户端决定要不要断开（规范：客户端撑不住这个版本就必须断开）
        String agreed = SUPPORTED_VERSIONS.contains(requested) ? requested : PREFERRED_VERSION;

        ObjectNode capabilities = MAPPER.createObjectNode();
        ObjectNode tools = MAPPER.createObjectNode();
        tools.put("listChanged", false);   // 我们的工具集是固定不变的
        capabilities.set("tools", tools);

        ObjectNode serverInfo = MAPPER.createObjectNode();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);

        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", agreed);
        result.set("capabilities", capabilities);
        result.set("serverInfo", serverInfo);
        result.put("instructions", INSTRUCTIONS);

        logStderr("initialize：客户端要 " + requested + "，协商结果 " + agreed
                + "，clientInfo=" + params.path("clientInfo").path("name").asText("?"));
        return result(id, result);
    }

    private ObjectNode toolsList(JsonNode id) {
        ArrayNode tools = MAPPER.createArrayNode();
        for (Tool tool : registry.all()) {
            tools.add(McpToolSchema.toMcpTool(MAPPER, tool));
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", tools);
        return result(id, result);
    }

    private ObjectNode toolsCall(JsonNode id, JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");

        Tool tool = registry.all().stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool == null) {
            // "工具不存在" 属于协议层错误（客户端根本没法自纠，是它自己拼错了名字）
            return errorResponse(id, INVALID_PARAMS, "工具不存在：" + name
                    + "。可用工具：" + registry.all().stream().map(Tool::name).toList());
        }

        // 从 arguments.input 取出那个壳里的字符串（对应输入 schema 里的 {"input": string}）
        String input = args.path("input").asText("");

        // MCP 没有"当前用户"这种概念（见下），只能从 _meta 里找，找不到就用默认值。
        // 这是本项目里 MCP 暴露出的最真实的一个缺口，详见 docs/mcp-notes.md
        String userId = params.path("_meta").path("userId").asText("mcp-anonymous");

        long started = System.currentTimeMillis();
        // ToolRegistry.execute 永不抛异常：失败信息本身就是要回给模型的内容
        ToolResult outcome = registry.execute(name, input, Map.of(MemorySaveTool.CTX_USER_ID, userId));
        long cost = System.currentTimeMillis() - started;
        logStderr("tools/call " + name + "(" + input + ") -> " + cost + "ms, error=" + outcome.error()
                + ", " + outcome.text().length() + " 字符");

        ObjectNode text = MAPPER.createObjectNode();
        text.put("type", "text");
        text.put("text", outcome.text());

        ObjectNode content = MAPPER.createObjectNode();
        ArrayNode arr = MAPPER.createArrayNode();
        arr.add(text);
        content.set("content", arr);

        // 规范：工具自身执行失败要用 isError 标出来，而不是 JSON-RPC error——
        // 这样模型能看见错误并自我纠正。这里直接读结构化字段，不去猜文本
        // （最初版本是猜的：[工具返回以「【」开头]，结果把正常的检索片段判成了错误，见 ToolResult 注释）
        if (outcome.error()) {
            content.put("isError", true);
        }
        return result(id, content);
    }

    private void requireInitialized(JsonNode id) {
        if (!initialized) {
            throw new NotInitializedException("还没收到 notifications/initialized，先完成 initialize 握手");
        }
    }

    // ---------- JSON-RPC 报文构造 ----------

    private ObjectNode result(JsonNode id, ObjectNode result) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.set("id", id);
        node.set("result", result);
        return node;
    }

    private ObjectNode errorResponse(JsonNode id, int code, String message) {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.set("id", id);
        node.set("error", error);
        return node;
    }

    /** 日志只写 stderr——stdout 是协议通道（规范允许 stderr 打日志） */
    private static void logStderr(String message) {
        System.err.println("[mcp] " + message);
    }

    private static final class NotInitializedException extends RuntimeException {
        NotInitializedException(String message) {
            super(message);
        }
    }
}
