# -*- coding: utf-8 -*-
"""
验证 MCP 规范里最容易被违反的一条：
  "The server MUST NOT write anything to its stdout that is not a valid MCP message."

做法：把 server 的 stdout 原始抓下来，逐行 JSON 解析。
只要有一行解析不了（banner / 日志 / 异常栈），就算违规。

对照组：故意把 System.setOut 那行注释掉重编一次，看这个脚本会不会红——
不然这个测试等于没测（永远绿的东西证明不了任何事）。
"""
import json
import os
import subprocess
import sys

# Windows 上 Python 的 stdout 默认是 GBK，中文输出会乱码/解码失败
sys.stdout.reconfigure(encoding="utf-8")

BASE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CP = BASE + "/target/classes;" + open(BASE + "/target/cp.txt", encoding="utf-8").read().strip()
JAVA = "java"
ARGS = ["-Dfile.encoding=UTF-8", "-cp", CP, "com.example.agentdemo.mcp.McpStdioServer"]

requests = [
    {"jsonrpc": "2.0", "id": 1, "method": "initialize",
     "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                "clientInfo": {"name": "purity-check", "version": "0.1"}}},
    {"jsonrpc": "2.0", "method": "notifications/initialized"},
    {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
    {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
     "params": {"name": "calculator", "arguments": {"input": "1+1"}}},
]
stdin_payload = "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in requests)

proc = subprocess.run([JAVA] + ARGS, input=stdin_payload.encode("utf-8"),
                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)

# --- 关键：逐行解析 stdout ---
out_lines = [l for l in proc.stdout.decode("utf-8").splitlines() if l.strip()]
err_text = proc.stderr.decode("utf-8", errors="replace")

bad = []
for i, line in enumerate(out_lines, 1):
    try:
        obj = json.loads(line)
        if obj.get("jsonrpc") != "2.0":
            bad.append((i, "JSON 合法但没有 jsonrpc 字段", line[:90]))
    except Exception as e:
        bad.append((i, "不是合法 JSON: %s" % e, line[:90]))

print("=" * 62)
print("stdout 纯净性检查（规范: MUST NOT write anything to stdout that is not a valid MCP message）")
print("=" * 62)
print("stdout 非空行数        = %d" % len(out_lines))
print("其中不是合法 MCP 报文的 = %d" % len(bad))
for i, why, sample in bad:
    print("  第 %d 行 %s | 样本: %r" % (i, why, sample))

print()
print("stderr 非空行数 = %d  （日志都在这，非 0 才说明日志确实没走 stdout）"
      % len([l for l in err_text.splitlines() if l.strip()]))
print("stderr 前 2 行（顺便验证 UTF-8）:")
for l in [l for l in err_text.splitlines() if l.strip()][:2]:
    print("   " + l)

print()
print("退出码 = %d" % proc.returncode)

ok = not bad and len(out_lines) == 3 and proc.returncode == 0
print()
print("结论: " + ("PASS —— stdout 全部是合法 MCP 报文" if ok
                 else "FAIL —— stdout 被污染了，见上面列出的行"))
sys.exit(0 if ok else 1)
