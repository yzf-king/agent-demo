# -*- coding: utf-8 -*-
"""诊断用：起一个 MCP server（可以是任意 main class），等它启动完再发请求。

和 mcp_client.py 的差别：这个会**先等**（Spring 应用要几秒才起来），
并且把 stdout / stderr 分开报告，用来定位"日志污染 stdout"这类问题。

用法：python mcp_probe.py <main-class>
"""
import json
import os
import subprocess
import sys
import threading
import time

sys.stdout.reconfigure(encoding="utf-8")

MAIN = sys.argv[1] if len(sys.argv) > 1 else "com.example.agentdemo.mcp.McpFrameworkServer"
WAIT = float(os.environ.get("PROBE_WAIT", "12"))

BASE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CP = BASE + "/target/classes;" + open(BASE + "/target/cp.txt", encoding="utf-8").read().strip()

env = dict(os.environ)
env.setdefault("AGENT_KNOWLEDGE_BASE_URL", "http://localhost:8081")

proc = subprocess.Popen(
    ["java", "-cp", CP, MAIN],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)

out_lines, err_lines = [], []


def pump(stream, sink, tag):
    for raw in iter(stream.readline, b""):
        sink.append(raw.decode("utf-8", "replace").rstrip())


threading.Thread(target=pump, args=(proc.stdout, out_lines, "OUT"), daemon=True).start()
threading.Thread(target=pump, args=(proc.stderr, err_lines, "ERR"), daemon=True).start()

print("main class = %s" % MAIN)
print("等 %ss 让它启动完……" % WAIT)
time.sleep(WAIT)

reqs = [
    {"jsonrpc": "2.0", "id": 1, "method": "initialize",
     "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                "clientInfo": {"name": "probe", "version": "1"}}},
    {"jsonrpc": "2.0", "method": "notifications/initialized"},
    {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
]
for r in reqs:
    if proc.poll() is not None:
        print("\n★ 进程已退出（退出码 %s），后面的请求发不出去了。" % proc.returncode)
        break
    line = json.dumps(r, ensure_ascii=False)
    try:
        proc.stdin.write((line + "\n").encode("utf-8"))
        proc.stdin.flush()
    except OSError as e:
        print("\n★ 写 stdin 失败（进程大概已经死了）：%s" % e)
        break
    time.sleep(1.2)          # 慢一点发，方便看是"哪一步不回"

time.sleep(3)
if proc.poll() is None:
    try:
        proc.stdin.close()
    except OSError:
        pass
    try:
        proc.wait(timeout=20)
    except subprocess.TimeoutExpired:
        proc.kill()

time.sleep(0.8)

print("\n" + "=" * 60)
print("stdout：共 %d 行，其中合法 JSON-RPC %d 行"
      % (len([l for l in out_lines if l.strip()]),
         len([l for l in out_lines if l.strip() and l.strip().startswith("{")])))
print("=" * 60)
for l in out_lines:
    if not l.strip():
        continue
    try:
        d = json.loads(l)
        print("  [JSON] id=%s keys=%s" % (d.get("id"), list(d.keys())))
        if "result" in d and "tools" in d.get("result", {}):
            print("         工具 = %s" % [t["name"] for t in d["result"]["tools"]])
    except Exception:
        print("  [★污染] %s" % l[:(1<<20) if os.environ.get("PROBE_FULL") else 150])

print()
print("=" * 60)
print("stderr：共 %d 行" % len([l for l in err_lines if l.strip()]))
print("=" * 60)
for l in err_lines[:12]:
    if l.strip():
        print("  %s" % l[:(1<<20) if os.environ.get("PROBE_FULL") else 150])

print()
print("退出码 = %s" % proc.returncode)
