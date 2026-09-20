# MCP 验证脚本

手写的 MCP server（`../src/main/java/com/example/agentdemo/mcp/`）的验证脚本。
放在仓库里而不是只在我本机跑过，是因为**"我测过"和"你能复现"是两回事**。

原理和踩坑记录见 [`../../docs/mcp-notes.md`](../../docs/mcp-notes.md)。

## 前置

```bash
mvn -q compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
```

`mcp_client.py` 的知识检索那条用例需要 rag-demo（作品 1）跑在 **8081** 上。
（另外两个脚本不需要任何下游服务。）

## 三个脚本

| 脚本 | 验什么 | 退出码 |
|---|---|---|
| `mcp_client.py` | 走一遍完整协议：握手 → 工具清单 → 成功调用 → 跨服务调用 → 未知工具 → 工具失败 → 不支持的方法 → ping。每步把收发原文打出来 | 0 = 全部断言通过 |
| `mcp_regression.py` | **错误语义**：成功不能有 `isError`、工具失败必须有、未知工具走协议错误、下游挂掉要 `isError:true` 且文案仍是人话 | 0 = 9/9 |
| `mcp_stdout_purity.py` | 规范那条 `MUST NOT write anything to stdout that is not a valid MCP message`：逐行 `json.loads`，一行解析不了就算违规 | 0 = stdout 干净 |

```bash
cd scripts/mcp
python mcp_client.py
python mcp_regression.py
python mcp_stdout_purity.py
```

## 关于"这个测试是不是恒绿"

写测试时最该问的一句是：**它有没有可能变红？** 这三个都有实测过的失败记录：

- `mcp_stdout_purity.py`：第一版没有把 `System.out` 重定向到 stderr，一定会红。
- `mcp_regression.py` 的 ①：修 `ToolResult` 之前，检索成功被误标成 `isError`，会红。
- `mcp_regression.py` 的 ⑤：`KnowledgeSearchTool` 还在 `catch` 里 `return` 一句文案时，
  `isError` 是 `None`，会红。

（对照做法：把 `McpStdioServer.captureProtocolStream()` 里那行重定向注释掉重编，
`mcp_stdout_purity.py` 应该立刻变红。没红就说明这个测试没在测东西。）

## 官方 Inspector（第三方验证）

上面三个都是我自己写的客户端——只能证明"实现符合我理解的规范"。
第三方验证用官方 Inspector：

```bash
# 依赖收进一个目录，避免 classpath 太长（Windows 命令行有长度限制）
mvn -q dependency:copy-dependencies -DoutputDirectory=target/dependency -DincludeScope=runtime
cp mcp-config.example.json mcp-config.json   # 把里面的路径改成你自己的
npx -y @modelcontextprotocol/inspector --cli \
    --config ./mcp-config.json --server agent-demo --method=tools/list --strict
```

`--strict` 会报工具 schema 的可移植性问题，有 error 级问题则退出码 6。

注意 `-cp` **不能直接写在 Inspector 的命令行上**（会被 commander 当成它自己的选项吃掉），
必须走配置文件——原因见 `../../docs/mcp-notes.md` §九。
