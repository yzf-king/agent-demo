# -*- coding: utf-8 -*-
"""
对比两条路在同一批请求下的应答情况，分两种发送节奏：

  A. 连着发（burst）   —— 请求背靠背写进去，不等待
  B. 一问一答（sequential）—— 发一条、等这条的响应回来、再发下一条

为什么非要分两种：**它们测出来的结论是相反的，只测一种就会得出片面结论。**

结果（框架版 MCP Java SDK 0.18.3）：
  A. 框架版随机丢 1～2 条响应，stderr 里是
     "Failed to enqueue message" + "Operator called default onErrorDropped"
     —— 多个执行线程同时往同一个 sink 写响应，输的那个错误被丢掉了，
     调用方永远收不到、也无从得知。手写版同样输入全部应答。
  B. 两条路都是 5/5，零丢包。

所以准确的结论是："框架版在并发下有一个手写版结构上不存在的竞态，
**合规客户端（一问一答）不会触发**"——不是"框架会丢请求"。

用法：
  python mcp_concurrency.py                     # 两条路都跑
  python mcp_concurrency.py <main-class>        # 只跑一条
"""
import json
import os
import sys
import time

sys.stdout.reconfigure(encoding="utf-8")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mcp_harness import HAND_WRITTEN, FRAMEWORK, default_boot_wait   # noqa: E402

import subprocess   # noqa: E402
import threading    # noqa: E402
from mcp_harness import CP   # noqa: E402

REQS = [
    {"jsonrpc": "2.0", "id": 1, "method": "initialize",
     "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                "clientInfo": {"name": "concurrency", "version": "1"}}},
    {"jsonrpc": "2.0", "method": "notifications/initialized"},
    {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
     "params": {"name": "calculator", "arguments": {"input": "1+1"}}},
    {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
     "params": {"name": "calculator", "arguments": {"input": "abc+*"}}},
    {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
     "params": {"name": "no_such_tool", "arguments": {"input": "x"}}},
]
EXPECTED = [r["id"] for r in REQS if "id" in r]


def run(main, burst):
    env = dict(os.environ)
    env["AGENT_KNOWLEDGE_BASE_URL"] = "http://127.0.0.1:59999"   # 死端口，免得依赖 8081
    p = subprocess.Popen(["java", "-cp", CP, main], stdin=subprocess.PIPE,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    out, err = [], []

    def pump(stream, sink):
        for raw in iter(stream.readline, b""):
            sink.append(raw.decode("utf-8", "replace").rstrip())

    threading.Thread(target=pump, args=(p.stdout, out), daemon=True).start()
    threading.Thread(target=pump, args=(p.stderr, err), daemon=True).start()
    time.sleep(default_boot_wait(main))

    def seen():
        ids = set()
        for l in out:
            if l.strip().startswith("{"):
                try:
                    ids.add(json.loads(l).get("id"))
                except Exception:
                    pass
        return ids

    def write(r):
        try:
            p.stdin.write((json.dumps(r, ensure_ascii=False) + "\n").encode("utf-8"))
            p.stdin.flush()
            return True
        except OSError:
            return False

    if burst:
        for r in REQS:
            if p.poll() is not None:
                break
            write(r)                      # 不等，全部一口气写完
        time.sleep(8)                     # 给足时间，证明是"不回来"而不是"回来得慢"
    else:
        for r in REQS:
            if p.poll() is not None:
                break
            if not write(r):
                break
            if "id" in r:
                deadline = time.time() + 15
                while time.time() < deadline and r["id"] not in seen():
                    time.sleep(0.02)

    try:
        p.stdin.close()
    except OSError:
        pass
    try:
        p.wait(timeout=30)
    except subprocess.TimeoutExpired:
        p.kill()
        p.wait()
    time.sleep(0.5)

    got = []
    for l in out:
        if l.strip().startswith("{"):
            try:
                got.append(json.loads(l).get("id"))
            except Exception:
                pass
    lost = [i for i in EXPECTED if i not in got]
    noisy = [l for l in err if "Failed to enqueue" in l or "onErrorDropped" in l]
    return got, lost, noisy, p.returncode


def main():
    targets = [sys.argv[1]] if len(sys.argv) > 1 else [HAND_WRITTEN, FRAMEWORK]
    print("=" * 70)
    print("并发下的应答情况对照（期望响应 id = %s）" % EXPECTED)
    print("=" * 70)
    verdict = []
    for m in targets:
        short = "框架版" if m == FRAMEWORK else "手写版"
        for burst, label in ((True, "A 连着发  "), (False, "B 一问一答")):
            got, lost, noisy, rc = run(m, burst)
            print("\n%s %-6s  收到 %d/%d  id=%s"
                  % (label, short, len(got), len(EXPECTED), sorted(x for x in got if x)))
            if lost:
                print("            ★丢失 id=%s；stderr 里 enqueue/onErrorDropped %d 行"
                      % (lost, len(noisy)))
                for l in noisy[:2]:
                    print("              %s" % l[:150])
            else:
                print("            无丢失；stderr 里 enqueue/onErrorDropped %d 行" % len(noisy))
            print("            退出码 = %s" % rc)
            verdict.append((short, burst, lost))

    print("\n" + "=" * 70)
    print("小结：")
    for short, burst, lost in verdict:
        print("  %-4s %-10s -> %s"
              % (short, "连着发" if burst else "一问一答",
                 ("丢 %d 条" % len(lost)) if lost else "全部应答"))
    print("\n  （结论要按节奏分开讲：一问一答是合规客户端的真实节奏，两条路都正常；")
    print("    连发才暴露框架版的竞态。别把 A 的结果说成「框架会丢请求」。）")


if __name__ == "__main__":
    main()
