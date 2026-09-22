# -*- coding: utf-8 -*-
"""
验证 MCP 规范里最容易被违反的一条：
  "The server MUST NOT write anything to its stdout that is not a valid MCP message."

做法：把 server 的 stdout 原始抓下来，逐行 JSON 解析。
只要有一行解析不了（banner / 日志 / 异常栈），就算违规。

**这条是框架版最反直觉的一处。**我原本想当然地在代码注释里写了
"框架会管住 stdout 纪律"，一跑才发现：日志、banner 全在 stdout 上，
17 行里 15 行是噪音。后来靠 logback-spring.xml 把日志挪到 stderr 才干净。
所以这个脚本对两条路都要跑，结论才有意义：
  python mcp_stdout_purity.py                                               # 手写版
  python mcp_stdout_purity.py com.example.agentdemo.mcp.McpFrameworkServer  # 框架版

对照组：把 McpStdioServer 里重定向 System.out 那行注释掉重编，
或者临时删掉 logback-spring.xml 的 mcp-stdio 分支，这个脚本必须变红——
不然它就是个永远绿的东西，证明不了任何事。
"""
import json
import os
import sys

# Windows 上 Python 的 stdout 默认是 GBK，中文输出会乱码/解码失败
sys.stdout.reconfigure(encoding="utf-8")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcp_harness import HAND_WRITTEN, run_server   # noqa: E402

MAIN = sys.argv[1] if len(sys.argv) > 1 else HAND_WRITTEN

requests = [
    {"jsonrpc": "2.0", "id": 1, "method": "initialize",
     "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                "clientInfo": {"name": "purity-check", "version": "0.1"}}},
    {"jsonrpc": "2.0", "method": "notifications/initialized"},
    {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
    {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
     "params": {"name": "calculator", "arguments": {"input": "1+1"}}},
]

msgs, proc = run_server(requests, main=MAIN)
out_lines = [l for l in proc.out_lines if l.strip()]
err_lines = [l for l in proc.err_lines if l.strip()]

print("=" * 62)
print("stdout 纯净性检查（规范: MUST NOT write anything to stdout that is not a valid MCP message）")
print("被测实现 = %s" % MAIN)
print("=" * 62)
print("stdout 非空行数        = %d" % len(out_lines))

# 逐行判：既是合法 JSON，又带 jsonrpc 字段，才算"MCP 报文"
bad = []
for i, line in enumerate(out_lines, 1):
    try:
        obj = json.loads(line)
        if obj.get("jsonrpc") != "2.0":
            bad.append((i, "JSON 合法但没有 jsonrpc 字段", line[:90]))
    except Exception as e:
        bad.append((i, "不是合法 JSON: %s" % e, line[:90]))

print("其中不是合法 MCP 报文的 = %d" % len(bad))
for i, why, sample in bad:
    print("  第 %d 行 %s | 样本: %r" % (i, why, sample))

print()
print("stderr 非空行数 = %d  （日志都在这，非 0 才说明日志确实没走 stdout）" % len(err_lines))
print("stderr 前 2 行（顺便验证 UTF-8）:")
for l in err_lines[:2]:
    print("   " + l)

print()
print("退出码 = %d" % proc.returncode)

# 成功条件三件套：stdout 无杂质、四条请求恰好三条响应（两条请求 + 一条通知）、正常退出。
# 数量对上很重要——只看"没有坏行"的话，一个啥都不输出的服务器也能全绿。
ok = not bad and len(out_lines) == 3 and proc.returncode == 0
print()
print("结论: " + ("PASS —— stdout 全部是合法 MCP 报文" if ok
                 else "FAIL —— stdout 被污染了，见上面列出的行"))
sys.exit(0 if ok else 1)
