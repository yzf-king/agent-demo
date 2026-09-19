# W3 原理笔记：Agent 循环到底是谁在转

> 目的：把"手写 ReAct"和"框架原生 Tool Calling"跑在同一批工具、同一个模型下，看清框架到底替我做了什么、又隐藏了什么。
> 代码：`agent/ReActAgent.java`（手写）、`agent/NativeToolAgent.java`（框架），工具在 `tools/`。启动后打开 http://localhost:8083 并排对比。

## 一、两条链路的真实差别

| 环节 | 手写 ReAct | 框架 Tool Calling |
|---|---|---|
| 工具怎么告诉模型 | 工具清单拼进 **system prompt**（`ToolRegistry.describe()`） | 走 API 的 **tools 参数**，框架从 `ToolDefinition` 生成 JSON Schema |
| 模型怎么表达"我要调工具" | 输出**文本**：`Action: xxx` / `Action Input: xxx` | 返回结构化的 **tool_calls**（函数名 + JSON 参数） |
| 谁来解析 | 我自己写正则（`ReActAgent` 里那四个 Pattern） | 框架，无需解析 |
| 谁来执行 | 我 `ToolRegistry.execute()` | 框架内部循环里执行 |
| 结果怎么回填 | 我手动拼 `Observation:` 追加进历史 | 框架自动追加 tool 消息 |
| 循环终止 | 我的 `for (step ≤ maxSteps)` | 框架内部循环 |
| 中间思考可见吗 | **看得到**（Thought 全文） | 看不到自然语言思考，只能靠工具回调埋点 |
| 模型每步耗时 | 测得到（我发起的调用） | **测不到**（调用在框架内部） |

一句话：**框架把"协议解析 + 执行 + 回填"三个环节接走了，代价是中间过程默认不可见。**
和 W2 那个"切分器看着能用、其实参数没生效"是同一类教训：省事的地方，正是你要多看一眼的地方。

## 二、实测（2026-09-19，deepseek-chat，本机 8083）

| 问题 | 手写 ReAct | 框架版 |
|---|---|---|
| 手册查远程办公天数，再按 4.3 周算（**检索 + 计算两步**） | 3 步 / 2287 ms | 2 次工具调用 / 3345 ms |
| 杭州今天天气 | 2 步 / 1434 ms | 1 次调用 / 1342 ms |
| (1200\*0.8)+50 等于多少 | 2 步 / 1062 ms | 1 次调用 / 1159 ms |

> 步数口径：手写版最后那个"给出 Final Answer"也算一步，所以「2 步」≈「1 次工具调用」，两边可比。
> 耗时口径**不一样**（手写版含模型调用，框架版只含工具执行），所以这三个耗时数字只能看个大概，不能当性能对比结论——这条已经写进 `AgentStep.costScope`，前端会标出来。

**两个实现都正确走完了多步推理**：第一步查手册拿到"每周最多两天"，第二步调计算器算 `2*4.3=8.6`，第三步给结论。模型没有跳过工具直接编答案。

## 三、踩坑：框架的 schema 要求参数是 object

`FunctionToolCallback.builder(name, fn).inputType(String.class)` 直接跑，DeepSeek 直接返回：

```
400 Invalid schema for function 'calculator':
schema must be a JSON Schema of 'type: "object"', got 'type: "string"'
```

原因：OpenAI 兼容的 tools 协议规定，函数的参数顶层必须是 object（因为要按名字取参数）。
`inputType(String.class)` 生成的是 `{"type":"string"}`，所以不合规。**修法**：包一层记录 `ToolInput(String input)`，schema 变成
`{"type":"object","properties":{"input":{"type":"string"}}}`，模型按 `{"input":"2*4.3"}` 调用。

手写 ReAct 完全没有这个约束——`Action Input` 本来就是一行自由文本。这条正好说明框架的作用范围：
**它标准化的是"模型与工具之间那一层协议"**，而你为此付出的代价，是工具的参数形状得按它的规则来。

## 四、工具本身怎么设计（比框架选型更重要）

- `description()` **直接决定模型会不会用、用得对不对**。把"输入是城市名，例如 杭州"写清楚，模型就老老实实传 `杭州`，
  而不是把整句问题塞进去（实测三条问题里，工具输入全部干净）。
- **工具要能失败得体面**：`ToolRegistry` 里查不到工具名、工具抛异常，都不往外抛，而是把错误当 `Observation` 回给模型，
  让它有机会自己改正。Agent 的鲁棒性一半在这。
- **安全边界**：`CalculatorTool` 宁可用 30 行递归下降解析器，也不上脚本引擎 eval——输入是模型给的字符串，
  交给 eval 等于把任意代码执行权交出去（提示词注入 → 参数注入 → 代码执行）。
- **工具可以换实现、不换接口**：`WeatherTool` 现在是硬编码的示例数据，换成真实 HTTP API 时 Agent 侧一行都不用改。
  `HandbookSearchTool` 是关键词版检索（没有 embedding），W4 把它换成调用 rag-demo 的检索接口，同样不用改 Agent。

## 五、还没验证的（别当结论讲）

- 框架内部那个循环的**步数上限**是多少、超限怎么表现——没测过，需要构造一个"模型反复调工具"的场景来压。
- 多轮对话记忆（W4 要做）：现在两个 Agent 都是无状态的，每问一句都是新会话。
- MCP：W3 计划里还有"跑通一个 MCP Server/Client"，**尚未开始**。
