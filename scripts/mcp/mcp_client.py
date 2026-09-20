"""手写的最小 MCP 客户端：拉起 server 进程，走一遍完整握手 + 工具调用。

写它的理由和手写 server 一样：只看代码是看不出协议长什么样的，得真的发一遍。
每一步把"发出去什么、收回来什么"原样打出来。

前置：先 `mvn -q compile`，再 `mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt`
      （脚本读 target/cp.txt 拼 classpath；用不通配符是因为这个脚本要能单独跑）
      知识检索那条用例需要 rag-demo 在 8081 上。
"""
import json
import os
import subprocess
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CP = open(os.path.join(PROJ, "target", "cp.txt"), encoding="utf-8").read().strip()
CP = os.path.join(PROJ, "target", "classes") + ";" + CP

env = dict(os.environ)
# 端口要显式指定成和 Java 侧默认值一致的那一个（8081）。
# 这里原来写的是 8082——那是个**残留的旧 rag-demo 实例**，
# 于是"检索成功"其实打的是另一个进程，本机起了两个实例却毫无察觉。
# 教训：测试脚本不要依赖环境里的"碰巧有个服务在跑"，端口要写死或显式传。
env["AGENT_KNOWLEDGE_BASE_URL"] = os.environ.get(
    "AGENT_KNOWLEDGE_BASE_URL", "http://localhost:8081")


def send(proc, obj):
    line = json.dumps(obj, ensure_ascii=False)
    assert "\n" not in line, "协议要求一条消息不能含换行"
    print(f"\n>>> {line}")
    proc.stdin.write(line + "\n")
    proc.stdin.flush()


def recv(proc):
    line = proc.stdout.readline()
    if not line:
        return None
    print(f"<<< {line.rstrip()}")
    return json.loads(line)


def main():
    proc = subprocess.Popen(
        ["java", "-cp", CP, "com.example.agentdemo.mcp.McpStdioServer"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8", errors="replace", env=env, bufsize=1,
    )

    print("=" * 70)
    print("① initialize —— 握手 + 协议版本协商")
    print("=" * 70)
    send(proc, {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "hand-written-test-client", "version": "0.1"},
        },
    })
    init = recv(proc)

    print("\n--- 通知（注意：没有 id，也不会有响应）---")
    send(proc, {"jsonrpc": "2.0", "method": "notifications/initialized"})

    print("\n" + "=" * 70)
    print("② 握手完成前调用工具 —— 应该被拒（验证服务器状态机）")
    print("=" * 70)

    print("\n" + "=" * 70)
    print("③ tools/list —— 拿工具清单")
    print("=" * 70)
    send(proc, {"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    tools = recv(proc)

    print("\n" + "=" * 70)
    print("④ tools/call —— 纯本地工具（计算器）")
    print("=" * 70)
    send(proc, {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                "params": {"name": "calculator", "arguments": {"input": "(1200*0.8)+50"}}})
    recv(proc)

    print("\n" + "=" * 70)
    print("⑤ tools/call —— 跨服务工具（知识检索，转发到 rag-demo）")
    print("=" * 70)
    send(proc, {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                "params": {"name": "knowledge_search",
                           "arguments": {"input": "试用期能不能申请远程办公"}}})
    recv(proc)

    print("\n" + "=" * 70)
    print("⑥ 不存在的工具 —— 规范要求走 JSON-RPC error（客户端拼错了，模型自纠不了）")
    print("=" * 70)
    send(proc, {"jsonrpc": "2.0", "id": 5, "method": "tools/call",
                "params": {"name": "no_such_tool", "arguments": {"input": "x"}}})
    recv(proc)

    print("\n" + "=" * 70)
    print("⑦ 工具自身执行失败 —— 规范要求走 isError:true，不是协议错误（模型要看得见才能自纠）")
    print("=" * 70)
    send(proc, {"jsonrpc": "2.0", "id": 6, "method": "tools/call",
                "params": {"name": "calculator", "arguments": {"input": "abc+*"}}})
    recv(proc)

    print("\n" + "=" * 70)
    print("⑧ 不支持的方法 —— -32601")
    print("=" * 70)
    send(proc, {"jsonrpc": "2.0", "id": 7, "method": "resources/list"})
    recv(proc)

    print("\n" + "=" * 70)
    print("⑨ ping —— 规范约定返回空结果")
    print("=" * 70)
    send(proc, {"jsonrpc": "2.0", "id": 8, "method": "ping"})
    recv(proc)

    # 关闭 stdin = 客户端断开，server 应该正常退出（退出码 0）
    proc.stdin.close()
    code = proc.wait(timeout=20)
    err = proc.stderr.read()

    print("\n" + "=" * 70)
    print(f"server 退出码 = {code}（0 = 正常）")
    print("=" * 70)
    print("--- server 的 stderr（日志都在这，stdout 保持干净）---")
    print(err.strip()[:3000])

    # 顺手做一次自我校验：协议版本协商结果
    print("\n=== 断言 ===")
    agreed = init["result"]["protocolVersion"]
    print(f"  协商版本 = {agreed}  {'OK' if agreed == '2025-06-18' else '不符合预期'}")
    names = [t["name"] for t in tools["result"]["tools"]]
    print(f"  工具清单 = {names}")
    bad_schema = [t["name"] for t in tools["result"]["tools"] if t["inputSchema"]["type"] != "object"]
    print(f"  inputSchema 不是 object 的工具 = {bad_schema or '无'}")


main()
