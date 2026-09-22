# -*- coding: utf-8 -*-
"""
MCP error-semantics 回归测试。

只测一件事：**什么错误该用什么形式返回**。这是 MCP 里最容易做错、也最影响
模型能不能自我纠正的一处设计，所以值得单独钉死：

  ① 成功            -> result，且 **不能有** isError       （曾经误标成 error，见 Bug B）
  ② 工具自身失败    -> result + isError:true               （模型看得见，能自纠）
  ③ 工具不存在      -> JSON-RPC error -32602               （客户端拼错名，模型自纠不了）
  ④ 不支持的方法    -> 合法的 -32601 或合法空结果          （见下面"为什么 ④ 放宽了"）
  ⑤ 下游服务挂了    -> result + isError:true               （曾经没有 isError，见 Bug C）

②用计算器输错表达式，⑤单独起一个把 base-url 指到死端口的 server 来造——
这样不管 8081 起没起，⑤都能稳定复现，不依赖"记得别启动 rag-demo"。

一个脚本同时打两条路（手写版 / 框架版），不然"对比"就退化成"各测各的、我心里记着"：
  python mcp_regression.py                                               # 手写版
  python mcp_regression.py com.example.agentdemo.mcp.McpFrameworkServer  # 框架版
"""
import json
import os
import sys

# Windows 上 Python 的 stdout 默认是 GBK：重定向到文件时写出去的就是 GBK 字节，
# 用 UTF-8 读会直接报 UnicodeDecodeError（这一步我确实踩了，脚本第一版没这行）。
sys.stdout.reconfigure(encoding="utf-8")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcp_harness import HAND_WRITTEN, run_server, explain   # noqa: E402

MAIN = sys.argv[1] if len(sys.argv) > 1 else HAND_WRITTEN

# 启动等待、发一条等一条的时序、以及 stderr 为什么要边跑边抽干——
# 都放在 mcp_harness 里统一实现（含踩坑原因），别在这边再抄一份。

results = []


def handshake(extra=()):
    return [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize",
         "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                    "clientInfo": {"name": "regression", "version": "1"}}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
    ] + list(extra)


def call(i, name, arg):
    return {"jsonrpc": "2.0", "id": i, "method": "tools/call",
            "params": {"name": name, "arguments": {"input": arg}}}


def check(label, cond, detail=""):
    results.append((label, cond, detail))
    print("  [%s] %s%s" % ("PASS" if cond else "FAIL", label,
                           ("  <- " + detail) if detail and not cond else ""))


print("=" * 66)
print("MCP error-semantics 回归（规范依据：tools 文档 + JSON-RPC 2.0）")
print("被测实现 = %s" % MAIN)
print("=" * 66)

# ---- 用真实下游跑 ①②③④ ----
msgs, proc = run_server(handshake([
    call(2, "calculator", "1+1"),
    call(3, "calculator", "abc+*"),
    call(4, "no_such_tool", "x"),
    {"jsonrpc": "2.0", "id": 5, "method": "resources/list"},
]))
explain(proc, "第一轮")
by_id = {}
for m in msgs:
    if "id" in m:
        by_id[m["id"]] = m

m = by_id.get(2, {})
res = m.get("result", {})
# isError 在规范里是可选字段，缺省即 false。手写版成功时**整个字段都不写**，
# 框架版则会显式写 "isError": false——两种都合规，所以这里只断言"不能是 true"。
# 有差异就打印出来，而不是用一个更严的断言把差异藏成失败。
check("① 成功 -> 有 result，且 isError 不为 true",
      "result" in m and res.get("isError") is not True,
      json.dumps(m, ensure_ascii=False)[:160])
print("      isError 字段形态：%s"
      % ("省略（规范允许）" if "isError" not in res else "显式写死 %r" % res.get("isError")))
check("   (顺带：结果里是算术答案而不是空)", "content" in res and "2" in res["content"][0]["text"])
# 顺带把文本是否被 JSON 二次编码也断言上：框架默认的 result converter
# 会把 String 也 JSON 化，于是 1+1 = 2 变成 "1+1 = 2"（带引号）。
# 这个不算协议错误，但会原样进模型上下文，属于"框架悄悄改了你的数据"，值得钉住。
check("   (文本是原样，没被 JSON 二次编码成带引号的字符串)",
      res.get("content", [{}])[0].get("text", "").startswith("1+1"),
      repr(res.get("content", [{}])[0].get("text", ""))[:80])

m = by_id.get(3, {})
res = m.get("result", {})
check("② 工具自身失败 -> result + isError:true",
      res.get("isError") is True and "error" not in m,
      json.dumps(m, ensure_ascii=False)[:200])
check("   (失败时文案仍然是人话，不是异常栈)",
      "这里应该是个数字" in res.get("content", [{}])[0].get("text", ""),
      json.dumps(res, ensure_ascii=False)[:160])

m = by_id.get(4, {})
check("③ 工具不存在 -> JSON-RPC error -32602（不是 isError）",
      m.get("error", {}).get("code") == -32602 and "result" not in m,
      json.dumps(m, ensure_ascii=False)[:200])

# ---- ④ 为什么放宽成"合法即可" ----
# 手写版只实现 tools，协议上就该回 -32601。框架版会**默认声明** resources/prompts/
# completions/logging 四项能力（即使我根本没提供任何 resource），于是 resources/list
# 返回 {"resources":[]} 而不是报错——客户端会看到一排空面板。
# 这既不是协议违规（空列表是合法结果），也不是"错误语义"问题，
# 而是"能力声明"问题，两件事不该混在同一个断言里，所以这里只断言"回应是规范的"，
# 具体差异记进 docs/mcp-notes.md 单独说。
m = by_id.get(5, {})
ok_unsupported = m.get("error", {}).get("code") == -32601 and "result" not in m
ok_empty_list = "result" in m and "error" not in m
check("④ 不支持的方法 -> 规范的 -32601，或声明了能力才返回的合法空结果",
      ok_unsupported or ok_empty_list,
      json.dumps(m, ensure_ascii=False)[:200])
print("      实际形态：%s" % ("JSON-RPC error -32601（未声明该能力）" if ok_unsupported
                            else "合法空结果 %s（服务器自己声明了该能力）"
                                 % json.dumps(m.get("result"), ensure_ascii=False)[:60]))

check("退出码 0（stdin 关闭即正常退出）", proc.returncode == 0, str(proc.returncode))

# ---- 用死端口的下游跑 ⑤ ----
msgs2, proc2 = run_server(
    handshake([call(2, "knowledge_search", "试用期能不能申请远程办公")]),
    env_extra={"AGENT_KNOWLEDGE_BASE_URL": "http://127.0.0.1:59999"},
)
explain(proc2, "第二轮")
got = [m for m in msgs2 if m.get("id") == 2]
check("⑤ 下游服务挂了 -> 有响应回来（没被启动时序吞掉）", len(got) == 1,
      "收到的 id = %s" % [m.get("id") for m in msgs2])
if got:
    r2 = got[0]
    res2 = r2.get("result", {})
    text2 = res2.get("content", [{}])[0].get("text", "")
    check("   下游挂了 -> result + isError:true", res2.get("isError") is True,
          json.dumps(r2, ensure_ascii=False)[:200])
    check("   (而且文案仍然人话，不是异常栈)",
          "知识库服务不可用" in text2 and "rag-demo" in text2, text2[:120])
check("   异常没有被写进 stdout 污染协议通道",
      not proc.bad_stdout_lines and not proc2.bad_stdout_lines,
      str((proc.bad_stdout_lines + proc2.bad_stdout_lines)[:2]))

print()
passed = sum(1 for _, c, _ in results if c)
print("结果：%d/%d 通过" % (passed, len(results)))
sys.exit(0 if passed == len(results) else 1)
