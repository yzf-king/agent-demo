# -*- coding: utf-8 -*-
"""
起一个 MCP stdio server 并跟它对话的公共部分。

为什么抽成模块而不是每个脚本抄一份：这套"启动等待 + 发一条等一条"的时序是有讲究的，
而且**我在这上面翻过车两次**（下面每条注释都对应一次真实误判）。
两份拷贝一旦有细微出入，就又变成"两条路的差异不知道是测出来的还是写歪的"——
这正是这个项目一直在防的那类错误，所以测试工具自己也得守同一条纪律。

手写版和框架版都能用同一个 run_server，靠 main 参数区分。

注意：这里用 "发一条、等一条"，**不是**为了好看，是唯一能同时满足下面两条的做法：
  · 框架版是 Spring 应用，请求必须在它启动完之后再发；
  · 框架版的多线程响应有竞态，连发会随机丢响应（见 mcp_regression.py 里的长注释）。
合规客户端本来就是一问一答，所以这个节奏既真实又稳定。
"""
import json
import os
import subprocess
import threading
import time

BASE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CP = BASE + "/target/classes;" + open(BASE + "/target/cp.txt", encoding="utf-8").read().strip()

HAND_WRITTEN = "com.example.agentdemo.mcp.McpStdioServer"
FRAMEWORK = "com.example.agentdemo.mcp.McpFrameworkServer"


def default_boot_wait(main):
    """框架版要好几秒才起来；手写版一启动就在读 stdin。

    踩坑记录：一开始我用 subprocess.run(input=...) 把所有请求一口气写进去就关 stdin，
    对框架版的结果是——响应整个不见、第二次干脆一条都没回，
    而我差点把这当成"框架版的错误语义有问题"。
    真相是应用还没起来 stdin 就被关了，服务器启动后直接看到 EOF 收摊。
    **先确认测试工具没坏，再怀疑被测对象。**
    """
    return float(os.environ.get("MCP_BOOT_WAIT", "14" if "Framework" in main else "0"))


def run_server(requests, main=HAND_WRITTEN, env_extra=None, boot_wait=None,
               per_req_timeout=20.0):
    """返回 (msgs, proc)。msgs 是解析成功的报文列表；proc 上挂了诊断用的字段。

    proc.bad_stdout_lines —— 解析不了的行（stdout 纯净性就靠它断言）
    proc.err_lines        —— stderr 全文（服务器没回话时，答案通常在这）
    proc.out_lines        —— stdout 原始行
    """
    if boot_wait is None:
        boot_wait = default_boot_wait(main)

    env = dict(os.environ)
    if env_extra:
        env.update(env_extra)

    p = subprocess.Popen(["java", "-cp", CP, main],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE, env=env)
    out_lines, err_lines = [], []

    def pump(stream, sink):
        # stderr 也必须边跑边抽干：一是要用它解释"服务器为什么死了"，
        # 二是 PIPE 缓冲区（Windows 上约 64KB）写满后子进程会**阻塞在写日志上**，
        # 表现出来就是"卡住没响应"，很容易被误判成协议问题。
        for raw in iter(stream.readline, b""):
            sink.append(raw.decode("utf-8", "replace").rstrip())

    threading.Thread(target=pump, args=(p.stdout, out_lines), daemon=True).start()
    threading.Thread(target=pump, args=(p.stderr, err_lines), daemon=True).start()
    time.sleep(boot_wait)

    def seen_ids():
        ids = set()
        for l in out_lines:
            if l.strip().startswith("{"):
                try:
                    ids.add(json.loads(l).get("id"))
                except Exception:
                    pass
        return ids

    for r in requests:
        if p.poll() is not None:
            break            # 进程已经死了，别再往里写（会 OSError: [Errno 22]）
        try:
            p.stdin.write((json.dumps(r, ensure_ascii=False) + "\n").encode("utf-8"))
            p.stdin.flush()
        except OSError:
            break
        if "id" in r:        # 通知没有 id，不等
            deadline = time.time() + per_req_timeout
            while time.time() < deadline and r["id"] not in seen_ids():
                time.sleep(0.02)

    time.sleep(0.5)          # 收个尾巴，再关 stdin——关早了在途请求会被一起带走
    try:
        p.stdin.close()
    except OSError:
        pass
    try:
        p.wait(timeout=60)
    except subprocess.TimeoutExpired:
        p.kill()
        p.wait()

    msgs, bad = [], []
    for l in [x for x in out_lines if x.strip()]:
        try:
            msgs.append(json.loads(l))
        except Exception as e:
            bad.append((l[:100], str(e)))
    p.bad_stdout_lines = bad          # 别让解析失败静默溜走
    p.err_lines = err_lines
    p.out_lines = out_lines
    return msgs, p


def explain(proc, tag):
    """一条响应都没有时，把 stderr 尾巴亮出来。
    没有这个，"服务器没回话"就只能靠猜——而实际情况几乎总是明写在 stderr 里。"""
    if proc.out_lines:
        return
    err = [l for l in proc.err_lines if l.strip()]
    print("\n  ★ %s 一条 stdout 都没有，stderr 最后 10 行：" % tag)
    for l in err[-10:]:
        print("      %s" % l[:220])
    print("      （stderr 共 %d 行）" % len(err))
