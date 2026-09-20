# -*- coding: utf-8 -*-
"""
MCP error-semantics 回归测试。

只测一件事：**什么错误该用什么形式返回**。这是 MCP 里最容易做错、也最影响
模型能不能自我纠正的一处设计，所以值得单独钉死：

  ① 成功            -> result，且 **不能有** isError       （曾经误标成 error，见 Bug B）
  ② 工具自身失败    -> result + isError:true               （模型看得见，能自纠）
  ③ 工具不存在      -> JSON-RPC error -32602               （客户端拼错名，模型自纠不了）
  ④ 不支持的方法    -> JSON-RPC error -32601
  ⑤ 下游服务挂了    -> result + isError:true               （曾经没有 isError，见 Bug C）

②用计算器输错表达式，⑤单独起一个把 base-url 指到死端口的 server 来造——
这样不管 8081 起没起，⑤都能稳定复现，不依赖"记得别启动 rag-demo"。
"""
import json
import os
import subprocess
import sys

# Windows 上 Python 的 stdout 默认是 GBK：重定向到文件时写出去的就是 GBK 字节，
# 用 UTF-8 读会直接报 UnicodeDecodeError（这一步我确实踩了，脚本第一版没这行）。
sys.stdout.reconfigure(encoding="utf-8")

BASE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CP = BASE + "/target/classes;" + open(BASE + "/target/cp.txt", encoding="utf-8").read().strip()

results = []


def run_server(requests, env_extra=None):
    env = dict(os.environ)
    if env_extra:
        env.update(env_extra)
    payload = "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in requests)
    p = subprocess.run(["java", "-cp", CP, "com.example.agentdemo.mcp.McpStdioServer"],
                       input=payload.encode("utf-8"),
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       env=env, timeout=180)
    raw_lines = [l for l in p.stdout.decode("utf-8").splitlines() if l.strip()]
    msgs, bad = [], []
    for l in raw_lines:
        try:
            msgs.append(json.loads(l))
        except Exception as e:
            bad.append((l[:100], str(e)))
    p.bad_stdout_lines = bad          # 供调用方断言，别让解析失败静默溜走
    return msgs, p


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
print("=" * 66)

# ---- 用真实下游跑 ①②③④ ----
msgs, proc = run_server(handshake([
    call(2, "calculator", "1+1"),
    call(3, "calculator", "abc+*"),
    call(4, "no_such_tool", "x"),
    {"jsonrpc": "2.0", "id": 5, "method": "resources/list"},
]))
by_id = {}
for m in msgs:
    if "id" in m:
        by_id[m["id"]] = m

m = by_id.get(2, {})
res = m.get("result", {})
check("① 成功 -> 有 result 且无 isError",
      "result" in m and "isError" not in res,
      json.dumps(m, ensure_ascii=False)[:160])
check("   (顺带：结果里是算术答案而不是空)", "content" in res and "2" in res["content"][0]["text"])

m = by_id.get(3, {})
res = m.get("result", {})
check("② 工具自身失败 -> result + isError:true",
      res.get("isError") is True and "error" not in m,
      json.dumps(m, ensure_ascii=False)[:200])

m = by_id.get(4, {})
check("③ 工具不存在 -> JSON-RPC error -32602（不是 isError）",
      m.get("error", {}).get("code") == -32602 and "result" not in m,
      json.dumps(m, ensure_ascii=False)[:200])

m = by_id.get(5, {})
check("④ 不支持的方法 -> -32601", m.get("error", {}).get("code") == -32601)

check("退出码 0（stdin 关闭即正常退出）", proc.returncode == 0, str(proc.returncode))

# ---- 用死端口的下游跑 ⑤ ----
msgs2, proc2 = run_server(
    handshake([call(2, "knowledge_search", "试用期能不能申请远程办公")]),
    env_extra={"AGENT_KNOWLEDGE_BASE_URL": "http://127.0.0.1:59999"},
)
r2 = [m for m in msgs2 if m.get("id") == 2][0]
res2 = r2.get("result", {})
text2 = res2.get("content", [{}])[0].get("text", "")
check("⑤ 下游服务挂了 -> result + isError:true",
      res2.get("isError") is True,
      json.dumps(r2, ensure_ascii=False)[:200])
check("   (而且文案仍然人话，不是异常栈)",
      "知识库服务不可用" in text2 and "rag-demo" in text2, text2[:120])
check("   (异常没有被写进 stdout 污染协议通道)",
      not proc.bad_stdout_lines and not proc2.bad_stdout_lines,
      str((proc.bad_stdout_lines + proc2.bad_stdout_lines)[:2]))

print()
passed = sum(1 for _, c, _ in results if c)
print("结果：%d/%d 通过" % (passed, len(results)))
sys.exit(0 if passed == len(results) else 1)
