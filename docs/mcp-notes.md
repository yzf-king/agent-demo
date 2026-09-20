# W4 原理笔记：MCP 到底替谁解决了什么

> 结论先放前面：**MCP 不神秘，它就是一个跑在 stdin/stdout 上的 JSON-RPC 2.0 服务。**
> 手写一遍之后你会发现协议本身只有四个方法，真正难的是两条纪律（stdout 不能脏、编码必须统一）
> 和一处设计（错误该用哪种形式返回）。
>
> 代码：`src/main/java/com/example/agentdemo/mcp/`（手写，不依赖 MCP SDK）
> 验证：自测 `mcp_regression.py` 9/9 + `mcp_stdout_purity.py` 纯净化断言，
> 外加**官方 MCP Inspector** 的第三方验证（`--strict` 零 schema 问题，`isError` 被官方客户端识别）。
> 两版协议的兼容性我 diff 过 schema，不是凭印象——见 §十。

## 一、为什么要先手写一遍

W3 已经用过一次这个套路：先手写 ReAct 循环，再换成 Spring AI 的 tool calling，
于是能说清楚"框架替我省了什么"。MCP 照做一遍。

手写之前的心理预期是"这是个新协议，得先学"。手写之后发现预期错了——
**它的新意不在传输层**（传输层就是 JSON-RPC，2005 年就有了），
**而在"把工具的定义和调用标准化了"**：同一个 server，Claude Desktop、IDE、
自己的 Agent 都能接。也就是说 MCP 解决的是**工具的分发问题**，不是能力问题。

这个区分很重要，面试里很容易被问："用了 MCP 是不是模型就变强了？"
不是。**MCP 让工具更容易被复用，不改变模型能不能用工具。**

## 二、协议本体：四个方法 + 一条分帧规则

一次完整的会话，服务端实际只用实现四个方法：

```
initialize                  客户端问"你支持哪些能力、什么协议版本"
notifications/initialized   客户端通知"握手完了"（注意：通知没有 id，不能回响应）
tools/list                  拿工具清单
tools/call                  调工具
```

其余（`ping`、`resources/list`、`prompts/list`）要么是可选能力，要么本例没实现。

**分帧规则是最容易踩错的一处**：一条消息 = 一行 JSON。
不是 LSP 那种 `Content-Length: 123\r\n\r\n{...}`，规范原文：

> Messages are delimited by newlines, and MUST NOT contain embedded newlines.

两个直接后果：

1. 序列化必须用 `writeValueAsString`，**不能用 pretty printer**——后者输出多行，直接破坏分帧。
2. 写入后必须 `flush()`，否则缓冲区里躺着，客户端一直等。

工具返回值里的换行不用管：JSON 序列化时会转义成 `\n`，物理上仍是一行。

## 三、第一条纪律：stdout 是协议通道，一个字节都不能脏

规范原文：

> The server MUST NOT write anything to its stdout that is not a valid MCP message.

这条最容易翻车，因为**框架默认就往 stdout 写**：Spring 的 banner、logback 的
ConsoleAppender 默认目标都是 stdout。只要漏出一行 `INFO ...`，客户端就解析失败——
而且报的错是"JSON 解析不了"，跟你实际的问题（打了一行日志）看起来毫无关系。

做法是在 `main` 的第一件事就把 `System.out` 换到 stderr：

```java
PrintStream realStdout = new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8);
System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, UTF_8));
```

规范明确允许往 stderr 打日志，所以日志不会丢，只是换了通道。

**怎么证明它真的干净**（这一点比"我这么写了"重要）：`mcp_stdout_purity.py` 把 server 的
stdout 原始抓下来逐行 `json.loads`，只要有一行解析不了就算违规。

实测结果：stdout 非空行 3 行、非法报文 0 行；同时 stderr 有 2 行日志。
**"stdout 干净"和"日志确实写到了 stderr"两件事同时成立**，才说明重定向生效了。

## 四、第二条纪律：UTF-8 要说两遍（真的踩了）

现象：JSON-RPC 报文结构完全正确，客户端也收得到，但**每个中文都是乱码**。

原因：`System.out` 这个 PrintStream 用**平台默认编码**（Windows 上是 GBK）编码字符，
而 MCP 走 UTF-8。英文完全正常，所以**不测中文就永远发现不了**。

顺带澄清一个常见误解：`-Dfile.encoding=UTF-8` 治不了这个病——
JDK 18+ 之后它只影响 API 的默认编码，管不到 `System.out` 这个已经构造好的流。
必须显式 `new PrintStream(..., UTF_8)`。

**这个坑我踩了第二次**：第一轮只换了 `System.out`，stdout 好了，
可 stderr 里我自己的 `[mcp]` 日志还是乱码。因为 `logStderr` 用的是 `System.err`，
而 `System.err` 从没被包过。有意思的是对照结果：

| 通道 | 谁写的 | 修之前 |
|---|---|---|
| stdout（协议） | 我 | 乱码 |
| stderr 里 logback 的行 | 框架 | **正常** |
| stderr 里 `[mcp]` 的行 | 我 | **乱码** |

logback 反而是好的，因为它的 ConsoleAppender 默认目标就是 `System.out`——
我重定向 `System.out` 时顺手把它一起带到了 stderr 上。
**所以"框架日志正常、自己的日志乱码"这个诡异现象，根因是两条流只换了一条。**

怎么证明修好了（不靠眼睛，因为控制台本身是 GBK，看着全是乱码）：
用 **strict** 模式解码 stderr，并断言中文子串真的在里面、且不含替换字符 U+FFFD。

实测：stdout / stderr 双向 strict-UTF8 解码 OK，无 U+FFFD。

## 五、第三条纪律：错误该用哪种形式返回（这里我错了两次，方向相反）

规范把两类"错误"分得很清楚，而且给了理由：

| 情况 | 返回形式 | 为什么 |
|---|---|---|
| **工具自己执行失败**（参数不合法、下游服务挂了） | `result` + `isError: true` | 规范理由：否则模型看不见错误，**也就没法自我纠正** |
| **工具不存在 / 方法不支持** | JSON-RPC `error` | 客户端自己拼错了，模型无从自纠 |

我在这条上犯了**两个方向相反的错**，都值得记：

### 错法一：把成功判成了失败

最初没有 `isError` 这个概念，靠猜文本：`observation.startsWith("【")` 就当失败。
结果 `knowledge_search` 成功返回的正常片段是这么开头的：

```
【片段 1｜相似度 0.636】
```

于是**每一次成功的检索都被标成了错误**。这是典型的"用文本特征去猜结构化信息"——
猜错了不报错，只会静默地把语义搞反。

修法是结构化的：引入 `ToolResult(text, error)`，
`ToolRegistry.execute` 返回它，MCP server 读字段而不是读文本。

### 错法二：把失败判成了成功

改完上面那处，我写了个测试断言"成功时不能有 isError"，结果**同一次测试顺带查出**：
下游 rag-demo（8081）没启动时，返回报文里 `isError` 是 `None`——客户端以为检索成功了。

根因在 `KnowledgeSearchTool`：

```java
} catch (RestClientException e) {
    return "知识库服务不可用（...）：请先启动 rag-demo（作品 1）。";   // 原来
}
```

工具自己把异常吞了、换成一句友好文案。这个写法在**只有 ReAct 一条路径**时是合理的
（那时候根本没有 error 标志，文本就是唯一的信息载体）。但引入 `ToolResult` 之后，
"能好好说话"和"能标记失败"被我当成了二选一——其实不是：

```java
throw new ToolFailureException("知识库服务不可用（...）：请先启动 rag-demo（作品 1）。");
```

`ToolRegistry` 会把消息原文包成 `【工具执行失败】...`，**文案照样友好，
error 标志也对了**，ReAct 那条路径拿到的观察内容基本没变。

> **这一处最值得讲的不是 bug 本身，是两次错误的对称性**：
> 同一条纪律，我从两个相反的方向各违反了一次。
> 共同点都是**没有把"失败"当成类型层面的东西**——一次用文本猜，一次干脆丢掉。

### 回归测试（9/9 通过）

```
① 成功            -> 有 result 且**无** isError
② 工具自身失败    -> result + isError:true            (calculator 输入 abc+*)
③ 工具不存在      -> JSON-RPC error -32602            (不是 isError)
④ 不支持的方法    -> JSON-RPC error -32601
⑤ 下游服务挂了    -> result + isError:true，且文案仍是人话
```

⑤ 是**专门造出来的**：把 `AGENT_KNOWLEDGE_BASE_URL` 指到死端口 `127.0.0.1:59999`，
这样不管 rag-demo 起没起都能稳定复现，不依赖"记得别启动它"。

顺带一个自我纠错：⑤ 那条断言第一版我写成了 `check(..., True)`——**永远会绿的测试等于没测**。
改成真的逐行 `json.loads` 之后才有意义。

## 六、和 W3 对上的一处：`inputSchema` 顶层必须是 object

规范里 `Tool.inputSchema.type` 是 `const "object"`。眼熟——W3 接 OpenAI tools 协议时
踩的是同一个坑：参数直接给 `String.class`，DeepSeek 回 400。

两个协议都这么要求，原因也一样：**要按名字取参数，参数就必须是个对象**
（JSON Schema 的 `properties` 只在 object 下有意义）。所以两边都得套一层壳：

```
我们的 Tool           进字符串、出字符串
OpenAI tools 协议     {"input": "..."}   ← ToolInput
MCP                   {"input": "..."}   ← McpToolSchema
```

## 七、同一批工具，现在有三种暴露方式

这是这个项目里横向对比价值最高的一点。**同一批 `Tool` 实现，一行都没为 MCP 改过**：

| 暴露方式 | 协议 | 谁解析工具调用 | 中间过程 |
|---|---|---|---|
| 手写 ReAct | 自定义文本协议（Thought/Action/Observation） | 我的正则 | 全可见（我自己记的） |
| Spring AI tool calling | OpenAI tools（结构化 schema） | 框架 | 默认不可见，要自己埋点 |
| MCP stdio server | JSON-RPC 2.0 | 客户端（可能是 Claude Desktop） | 可见（我能记日志） |

（唯一动过的 `KnowledgeSearchTool` 是上面那个错误语义 bug，不是为了适配 MCP。）

## 八、MCP 暴露出的一个真实缺口：它不知道"当前用户"是谁

MCP 的 `tools/call` 参数里只有工具名和 arguments，**没有任何"谁在调用"的概念**。
而 W4 的 `memory_save` 工具必须知道 userId，且这个值**不能来自模型**
（模型不知道，你让它传它只会编，甚至可能编成别人的）。

MCP 给了两个都不太理想的出口：

- `_meta` 字段（协议允许放额外元数据）——目前实现里就是这么读的，
  取不到就退化成 `mcp-anonymous`。
- 塞进 arguments——但那会进模型的视野，退回到"让模型传 userId"的老问题。

**这是手写一遍才能看清的**：框架文档只会告诉你"怎么传上下文"，
不会告诉你"这个协议本身没有身份概念，所以你要自己决定把身份放在哪一层"。
生产上真正的答案是：stdin 起的进程拿不到 HTTP 的网关身份，得由**启动它的宿主**
注入环境变量或在握手时协商。这条我还没做。

另有一处相关的实现细节：stdio server 是**独立进程**，
里面 `new UserProfileStore(20, 200)` 出来的内存，和 Web 服务（8083）那个**不共享**。
这不是 bug，是"内存态记忆不跨实例"的真实体现——同一条已经写在 README 的已知局限里。

## 九、第三方验证：官方 MCP Inspector

自测有个绕不过去的效力边界：**它只能证明"我的实现符合我理解的规范"，
证明不了"我的理解就是规范"**。所以必须拿官方客户端验一遍。

### 怎么跑起来（Windows 上折腾了几轮，记下来）

Inspector 的 CLI 模式不接受命令行上的 `-cp`——**`-cp` 会被 commander 当成自己的选项吃掉**，
然后类名就成了"目标服务器"，报出来的是 Java 的 usage，看着跟 MCP 毫无关系。
试过三种传参顺序，只有走配置文件这条路是通的：

```json
{
  "mcpServers": {
    "agent-demo": {
      "command": "java",
      "args": ["-Dfile.encoding=UTF-8", "-cp",
               "F:/.../target/classes;F:/.../target/dependency/*",
               "com.example.agentdemo.mcp.McpStdioServer"]
    }
  }
}
```

```bash
npx -y @modelcontextprotocol/inspector --cli \
    --config ./mcp-config.json --server agent-demo --method=tools/list
```

顺带说明：**这个配置文件的格式就是 Claude Desktop 的配置格式**，
所以这份文件不只是测试用的，接 Claude Desktop 时还是它。

中间踩的两个坑（都属于"和 MCP 无关、但会让你以为 MCP 出问题了"）：
- **命令行太长**：classpath 展开有 9007 字节，npx→node→spawn 这条链撑不住。
  解法是 `mvn dependency:copy-dependencies` 把依赖收进一个目录，用 `dir/*` 通配符——
  从 9007 字节缩到 100 字节以内。
- **`@argfile` 也试过，失败**：Java 读参数文件用的是**平台默认编码**，
  而我写进去的是 UTF-8 的中文路径（项目在 `F:\转型计划\`），路径直接解错，
  报的是 `ClassNotFoundException`。这条本身值得记：**中文路径 + 非 ASCII 的参数文件是个雷区。**

### 官方客户端验到了什么

| 项 | 结果 |
|---|---|
| 握手 | 成功，`clientInfo=inspector-cli` |
| 工具清单 | 5 个工具全部拿到 |
| **工具 schema 可移植性**（`--strict`） | **退出码 0，零 error 级问题** |
| 工具成功调用 | `calculator input=1+1` → `1+1 = 2` |
| 工具失败调用 | 我的 `isError:true` 被官方客户端**认出来**：`{"code":"tool_is_error"}` |

最后一行是这次最有价值的验证：`isError` 这个设计不是我自己觉得对，
是**官方客户端真的会读这个字段并把它当错误**。

### 但它没验到什么（这个边界要说清）

试"不存在的工具"时，Inspector 返回 `tool_not_found`，可是**我的 server 根本没收到这个请求**——
它从 `tools/list` 就知道没有这个名字，在客户端侧就拦下了。
（判断依据：那次输出里只有 `initialize` 的日志，没有 `tools/call` 的日志。）

所以：
- **`-32602`（工具不存在）这条路，官方客户端没走过**，是我自己写的客户端验的。
- `-32601`（不支持的方法）、通知的处理，同样没有被第三方验过。
- **Claude Desktop 接入仍然没试过。**
- **Spring AI MCP starter 版还没写**，"框架替我省了什么"这半段对比目前是空的。

## 十、顺带查实的一件事：我说"支持 2025-11-25"有依据吗

Inspector 协商时**要的是 `2025-11-25`**，而我只读过 2025-06-18 的规范就把它列进了
`SUPPORTED_VERSIONS`。它这次碰巧通了——但"碰巧通了"不是依据，得去验。

先读 [changelog](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2025-11-25/changelog.mdx)：
新增的都是**可选能力**（OIDC 发现、图标、elicitation 枚举、sampling 的工具调用、实验性的 tasks）。

但"新增都是可选的"仍然只是印象。于是把两版 schema 的 `$defs` 拉下来，
对我用到的那几个定义逐条 diff：

| 定义 | 差异 |
|---|---|
| `InitializeResult` / `CallToolResult` / `ListToolsResult` | **只有文档链接和 `$ref` 路径**（`#/definitions/` → `#/$defs/`），零语义变化 |
| `Tool.inputSchema.type` | 两版都是 `{"const": "object"}` 且 `required: ["type"]` —— **完全一致** |
| `Tool` | 多了 `execution`、`icons`，都是**可选**字段 |
| `InitializeRequest.required` | 06-18 是 `[method, params]`，11-25 是 `[id, jsonrpc, method, params]` |

最后一行是唯一的实质变化，而且**在请求侧**：11-25 明确要求请求带 `id`/`jsonrpc`。
我的响应本来就带这两个字段，所以不受影响。

结论（有依据的版本）：**我实现的这四个方法在两版之间语义兼容，
差异只有文档引用、`$ref` 写法、两个可选新字段，以及请求侧一处 required 收紧。**

> 这段本身比结论更值得说：一开始我写的是"它碰巧通了"，
> 停在这里就等于**把没验证的东西当成已知**。多花十分钟 diff 一遍 schema，
> 才把"我以为"换成了"我查过"。

## 十一、还没验证的（别当结论讲）

- **Claude Desktop 接入没试过**（配置文件已经有了，见 §九）。
- **Spring AI MCP starter 版还没写**，"框架替我省了什么"这半段对比目前是空的。
- **客户端报一个我不支持的版本时，客户端会不会按规范断开**——没测。
- **并发**：没测过多个客户端同时连。
- Inspector 验不了的那几条见 §九末尾（`-32602`、`-32601`、通知）。
