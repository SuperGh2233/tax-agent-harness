#!/usr/bin/env python3
"""T40 fault injection against the real JAR: what T36 covered only with in-process fakes (FaultRecoveryTest,
MalformedReplyConsistencyTest, the MockMvc stream tests). A loopback stub speaks the Anthropic Messages protocol in
place of the model, so each fault is exact and repeatable: a reply slower than the 60-second timeout, HTTP 429, a
malformed reply (once and twice), a model that keeps calling a tool that fails, a server killed (SIGKILL) after the
approved save committed but before the reply reached the client, a server killed before the approved turn wrote
anything, and a client that leaves the stream in the middle of an approved save.

Every check is judged from program evidence: error codes, the requests the stub received, the database (purpose of
F002 and the invoice_operations ledger) and /verify after a restart. After the first kill an UPDATE trigger on
invoices makes any second write fail, so "not redone" is proven by the database, not by a count alone. No real
model is called and no key is needed; the server never sees the shell's LLM_* variables.

Usage (JDK 21; about two minutes, one of which is the timeout scenario):
    python3 scripts/fault_injection_check.py --jar target/harness-0.0.1-SNAPSHOT.jar
    python3 scripts/fault_injection_check.py --jar ... --skip timeout
Output: <out>/results.json, <out>/summary.md and one server log per start.
"""
import argparse, http.client, json, os, socket, sqlite3, subprocess, sys, tempfile, threading, time, urllib.parse, urllib.request, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from innovation_compare import Client, VALUE, code, database_state, free_port, history  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
CORRECTION = "上一次回复无法被解析"  # LlmClient.CORRECTION
QUERY = "查询 C001 2026 年 9 月的发票"
SAVE = "现在保存以下经我逐项核对的精确修改，并查询发票核对：" + json.dumps(
    {"enterprise": "C001", "invoiceId": "F002", "changes": {"purpose": VALUE}}, ensure_ascii=False, indent=2)
FOREIGN = {"tool": "query_invoices", "input": {"enterprise_id": "C002", "month": "2026-09"}}
SAVE_CALL = {"tool": "update_invoice", "input": {}}


class Stub:
    """Answers POST /v1/messages from a script of actions; records what each request carried."""

    def __init__(self):
        self.lock, self.script, self.requests = threading.Lock(), [], []
        self.held, self.release = threading.Event(), threading.Event()
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
                with stub.lock:
                    stub.requests.append(dict(summarize(body), overflow=not stub.script))
                    action = stub.script.pop(0) if stub.script else {"finish": "（桩模型脚本已用完）"}
                    n = len(stub.requests)
                try:
                    if action.get("hold"):
                        stub.held.set(); stub.release.wait(180)
                    time.sleep(action.get("delay", 0))
                    if "status" in action: return self.reply(action["status"], {"type": "error", "error": {"type": "rate_limit_error"}})
                    if action.get("malformed"): return self.reply(200, "这不是 JSON")
                    if "tool" in action:
                        content, stop = [{"type": "tool_use", "id": f"toolu_stub_{n}", "name": action["tool"], "input": action["input"]}], "tool_use"
                    else:
                        content = [{"type": "text", "text": json.dumps({"kind": "finish", "text": action.get("finish", "（已释放）")}, ensure_ascii=False)}]
                        stop = "end_turn"
                    self.reply(200, {"id": f"msg_stub_{n}", "type": "message", "role": "assistant", "model": "stub",
                                     "stop_reason": stop, "content": content})
                except Exception: pass  # the server gave up on this request (timeout or killed)

            def reply(self, status, body):
                data = (body if isinstance(body, str) else json.dumps(body, ensure_ascii=False)).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data)))
                self.end_headers(); self.wfile.write(data)

            def log_message(self, *args): pass

        self.server = ThreadingHTTPServer(("127.0.0.1", free_port()), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.endpoint = f"http://127.0.0.1:{self.server.server_address[1]}"

    def load(self, *actions):
        with self.lock:
            self.script, self.requests = list(actions), []
        self.release.set(); self.held, self.release = threading.Event(), threading.Event()


def summarize(body):
    """Request evidence without the prompt text: correction marker, message count, the last tool results."""
    messages = body.get("messages") or []
    last = messages[-1].get("content") if messages else None
    results = [b.get("content", "") for b in last if isinstance(b, dict) and b.get("type") == "tool_result"] if isinstance(last, list) else []
    return {"correction": CORRECTION in (body.get("system") or ""), "messages": len(messages),
            "tools": [t.get("name") for t in body.get("tools") or []], "toolResults": [r[:400] for r in results],
            "verifiedResult": any('"verified":true' in r.replace(" ", "") for r in results)}


class Jar:
    """The real JAR on a database, pointed at the stub; started and stopped (or killed) per scenario."""

    def __init__(self, jar, stub, out):
        self.jar, self.stub, self.out, self.starts, self.process = jar, stub, out, 0, None
        env = {k: v for k, v in os.environ.items() if not k.startswith("LLM_")}
        env.update(LLM_ENDPOINT=stub.endpoint, LLM_API_KEY="stub-key", LLM_MODEL="stub-model", LLM_PROTOCOL="anthropic")
        self.env = env

    def fresh(self, name):
        db = Path(tempfile.mkdtemp(prefix=f"t40-{name}-")) / "demo.db"
        subprocess.run(["java", "-jar", str(self.jar), "--initialize-data", f"--database={db}"], check=True, stdout=subprocess.DEVNULL)
        return db

    def start(self, db, name):
        self.starts += 1
        port = free_port()
        self.log = (self.out / f"server-{self.starts:02d}-{name}.log").open("w")
        self.process = subprocess.Popen(["java", "-jar", str(self.jar), f"--taxagent.database={db}", "--server.address=127.0.0.1",
                                         f"--server.port={port}"], env=self.env, stdout=self.log, stderr=subprocess.STDOUT)
        base = f"http://127.0.0.1:{port}"
        for _ in range(120):
            try:
                with urllib.request.urlopen(base + "/api/health", timeout=2) as r:
                    if r.status == 200: return base
            except Exception: time.sleep(0.5)
        raise RuntimeError(f"server not ready; see {self.log.name}")

    def stop(self, kill=False):
        if kill: self.process.kill()
        else: self.process.terminate()
        try: self.process.wait(timeout=20)
        except subprocess.TimeoutExpired: self.process.kill(); self.process.wait()
        self.log.close()


def operator(base):
    c = Client(base)
    _, session = c.call("GET", "/api/demo/session"); c.csrf = session["csrfToken"]
    _, session = c.call("POST", "/api/demo/session", {"profile": "operator", "enterprise": "C001"}); c.csrf = session["csrfToken"]
    return c


def start_task(c, goal, pending=()):
    status, task = c.call("POST", "/api/demo/tasks", {"goal": goal, "pending": list(pending)})
    if status != 200: raise RuntimeError(f"task start HTTP {status} {task}")
    return task["id"]


def task_status(c, task_id):
    _, view = c.call("GET", f"/api/demo/tasks/{task_id}")
    return ((view or {}).get("task") or {}).get("status")


def text(view):
    return (((view or {}).get("turn") or {}).get("result") or {}).get("text") or ""


def approval():
    return {"operationId": str(uuid.uuid4()), "invoiceId": "F002", "changes": {"purpose": VALUE}, "confirmed": True}


def background(fn):
    """Runs a blocking request; the holder gets ("ok", value) or ("lost", exception class)."""
    box = {}
    def run():
        try: box["outcome"] = ("ok", fn())
        except Exception as e: box["outcome"] = ("lost", type(e).__name__)
    thread = threading.Thread(target=run, daemon=True); thread.start()
    return thread, box


def add_no_update_trigger(db):
    with sqlite3.connect(db) as c:
        c.execute("CREATE TRIGGER t40_no_second_update BEFORE UPDATE ON invoices BEGIN SELECT RAISE(ABORT,'t40: must not write again'); END")


class Checks:
    def __init__(self): self.items = []

    def add(self, scenario, name, ok, detail):
        self.items.append({"scenario": scenario, "check": name, "passed": bool(ok), "detail": detail})
        print(f"{'PASS' if ok else 'FAIL'}  [{scenario}] {name}  {detail}", flush=True)


def model_failure(jar, stub, checks, name, actions, expected, requests, correction=None, seconds=None):
    """One query turn against a faulty model: expected code, how often the model was asked, nothing changed."""
    db = jar.fresh(name)
    base = jar.start(db, name)
    try:
        c = operator(base)
        task = start_task(c, "查询九月发票")
        stub.load(*actions)
        started = time.time()
        _, view = c.call("POST", f"/api/demo/tasks/{task}/turn", {"message": QUERY}, timeout=180)
        elapsed = round(time.time() - started, 1)
        got = code(view)
        checks.add(name, f"错误码为 {expected or '无'}", got == expected, f"实际 {got}；耗时 {elapsed}s")
        checks.add(name, f"模型共被请求 {requests} 次（不自动重发）", len(stub.requests) == requests,
                   f"实际 {len(stub.requests)} 次；纠正标记 {[r['correction'] for r in stub.requests]}")
        if correction is not None:
            checks.add(name, "只有重发的那次请求带纠正说明", [r["correction"] for r in stub.requests] == correction,
                       f"纠正标记 {[r['correction'] for r in stub.requests]}")
        if seconds is not None:
            checks.add(name, f"在约 {seconds[1]} 秒超时处结束", seconds[0] <= elapsed <= seconds[1] + 15, f"耗时 {elapsed}s")
        if expected:
            reply = text(view)
            needs = ["没有修改任何发票"] + (["不会自动重发"] if expected.startswith("MODEL_") else []) + (["连续 3 次"] if expected == "TOOL_ERRORS_REPEATED" else [])
            checks.add(name, "失败回复说明真实状态", all(n in reply for n in needs) and "迟到" not in reply, f"应含 {needs}；回复前 120 字：{reply[:120]}")
            checks.add(name, "任务保持开放", task_status(c, task) == "ACTIVE", f"状态 {task_status(c, task)}")
        if expected == "TOOL_ERRORS_REPEATED":
            codes = [((json.loads(e.get("resultJson") or "{}").get("error") or {}).get("code")) for e in history(view)]
            checks.add(name, "第 3 次工具错误后即停止", len(codes) == 3 and all(codes), f"工具结果错误码 {codes}")
        purpose, ledger = database_state(db)
        checks.add(name, "数据库未被修改", ledger == 0 and purpose == "", f"F002 用途 {purpose!r}；台账 {ledger} 条")
    finally:
        jar.stop()


def lost_response(jar, stub, checks):
    """The save commits, the server dies before replying; after restart it is verified and never written again."""
    name = "lost-response"
    db = jar.fresh(name)
    base = jar.start(db, name)
    c = operator(base)
    task = start_task(c, "保存 F002 并补 F004", ["F004.purpose"])  # F004 stays pending, so the task stays open
    approved = approval()
    stub.load(SAVE_CALL, {"hold": True})
    thread, box = background(lambda: c.call("POST", f"/api/demo/tasks/{task}/turn", {"message": SAVE, "approval": approved}, timeout=180))
    held = stub.held.wait(60)
    purpose, ledger = database_state(db)
    saved = held and stub.requests[-1]["verifiedResult"]
    checks.add(name, "保存已提交、模型正等待回复时", held and ledger == 1 and purpose == VALUE and saved,
               f"桩模型收到第 2 次请求 {held}；F002 用途 {purpose!r}；台账 {ledger} 条；回给模型的工具结果含 verified=true {saved}")
    jar.stop(kill=True)
    thread.join(30)
    outcome = box.get("outcome", ("pending", None))
    checks.add(name, "客户端没有收到回复（进程被 SIGKILL）", outcome[0] == "lost", f"客户端结果 {outcome[0]} {outcome[1] if outcome[0] == 'lost' else ''}")
    add_no_update_trigger(db)
    base = jar.start(db, name + "-restart")
    try:
        c = operator(base)
        checks.add(name, "重启后任务仍开放（那一轮没有保存）", task_status(c, task) == "ACTIVE", f"状态 {task_status(c, task)}")
        status, verified = c.call("POST", f"/api/demo/tasks/{task}/operations/{approved['operationId']}/verify")
        checks.add(name, "重启后按 operationId 只读核验为 VERIFIED", status == 200 and (verified or {}).get("status") == "VERIFIED"
                   and ((verified or {}).get("verification") or {}).get("verified") is True, f"HTTP {status}；{(verified or {}).get('status')}")
        stub.load(SAVE_CALL, {"finish": "核对已有保存"})
        status, again = c.call("POST", f"/api/demo/tasks/{task}/turn", {"message": SAVE, "approval": approved}, timeout=180)
        purpose, ledger = database_state(db)
        checks.add(name, "重发同一批准：复用台账、不再写入", status == 200 and code(again) is None
                   and ((again or {}).get("verification") or {}).get("verified") is True and ledger == 1 and purpose == VALUE,
                   f"HTTP {status}；错误码 {code(again)}；verified {((again or {}).get('verification') or {}).get('verified')}；台账 {ledger} 条；第二次 UPDATE 会被触发器拒绝")
        checks.add(name, "任务等待 F004 补充", task_status(c, task) == "WAITING_USER", f"状态 {task_status(c, task)}")
    finally:
        jar.stop()


def never_ran(jar, stub, checks):
    """The server dies before the approved turn wrote anything; after restart the approval is gone."""
    name = "never-ran"
    db = jar.fresh(name)
    base = jar.start(db, name)
    c = operator(base)
    task = start_task(c, "保存 F002")
    approved = approval()
    stub.load({"hold": True})
    thread, box = background(lambda: c.call("POST", f"/api/demo/tasks/{task}/turn", {"message": SAVE, "approval": approved}, timeout=180))
    held = stub.held.wait(60)
    purpose, ledger = database_state(db)
    checks.add(name, "批准轮在第一次模型请求处中断，尚未写入", held and ledger == 0 and purpose == "", f"台账 {ledger} 条；F002 用途 {purpose!r}")
    jar.stop(kill=True)
    thread.join(30)
    checks.add(name, "客户端没有收到回复（进程被 SIGKILL）", box.get("outcome", ("pending",))[0] == "lost", f"客户端结果 {box.get('outcome')}")
    base = jar.start(db, name + "-restart")
    try:
        c = operator(base)
        status, verified = c.call("POST", f"/api/demo/tasks/{task}/operations/{approved['operationId']}/verify")
        checks.add(name, "重启后核验为 NOT_FOUND", status == 200 and (verified or {}).get("status") == "NOT_FOUND", f"HTTP {status}；{(verified or {}).get('status')}")
        stub.load(SAVE_CALL, {"finish": "已保存"})
        _, view = c.call("POST", f"/api/demo/tasks/{task}/turn", {"message": "继续刚才的保存"}, timeout=180)
        purpose, ledger = database_state(db)
        checks.add(name, "无批准的继续请求被拒绝，发票未改", code(view) == "CONFIRMATION_REQUIRED" and "没有修改任何发票" in text(view)
                   and ledger == 0 and purpose == "", f"错误码 {code(view)}；台账 {ledger} 条；F002 用途 {purpose!r}；回复前 80 字：{text(view)[:80]}")
    finally:
        jar.stop()


def stream_disconnect(jar, stub, checks):
    """The client leaves the stream while the approved save is in flight; the turn still completes and verifies."""
    name = "stream-disconnect"
    db = jar.fresh(name)
    base = jar.start(db, name)
    try:
        c = operator(base)
        task = start_task(c, "保存 F002")
        approved = approval()
        stub.load(SAVE_CALL, {"delay": 3, "finish": "已保存并核对"})
        cookies = next(h.cookiejar for h in c.http.handlers if isinstance(h, urllib.request.HTTPCookieProcessor))
        connection = http.client.HTTPConnection("127.0.0.1", urllib.parse.urlparse(base).port, timeout=30)
        connection.request("POST", f"/api/demo/tasks/{task}/turn/stream",
                           json.dumps({"message": SAVE, "approval": approved}, ensure_ascii=False).encode(),
                           {"Content-Type": "application/json", "X-CSRF-Token": c.csrf,
                            "Cookie": "; ".join(f"{k.name}={k.value}" for k in cookies)})
        response, first = connection.getresponse(), ""
        for _ in range(20):  # the status event: its event line, then the data line carrying "started"
            line = response.readline().decode(errors="replace")
            first += line
            if not line or "started" in line: break
        # Leave for real: shut the socket down rather than wait for garbage collection to close it.
        if connection.sock: connection.sock.shutdown(socket.SHUT_RDWR)
        connection.close()
        checks.add(name, "收到 started 后客户端断开", "started" in first, f"断开前收到 {first.strip()[:80]!r}")
        deadline, status = time.time() + 30, None
        while time.time() < deadline:
            _, verified = c.call("POST", f"/api/demo/tasks/{task}/operations/{approved['operationId']}/verify")
            status = (verified or {}).get("status")
            if status == "VERIFIED" and task_status(c, task) == "COMPLETED": break
            time.sleep(1)
        purpose, ledger = database_state(db)
        checks.add(name, "断开后本轮照常完成并核验", status == "VERIFIED" and ledger == 1 and purpose == VALUE and len(stub.requests) == 2,
                   f"核验 {status}；台账 {ledger} 条；F002 用途 {purpose!r}；模型请求 {len(stub.requests)} 次")
        checks.add(name, "本轮结果已保存，任务结项", task_status(c, task) == "COMPLETED", f"状态 {task_status(c, task)}")
    finally:
        jar.stop()


SCENARIOS = {
    "timeout": lambda j, s, c: model_failure(j, s, c, "timeout", [{"delay": 70, "finish": "迟到的成功"}], "MODEL_TIMEOUT", 1, seconds=(55, 60)),
    "rate-limit": lambda j, s, c: model_failure(j, s, c, "rate-limit", [{"status": 429}], "MODEL_RATE_LIMITED", 1),
    "malformed-twice": lambda j, s, c: model_failure(j, s, c, "malformed-twice", [{"malformed": True}] * 2, "MODEL_RESPONSE_INVALID", 2, [False, True]),
    "malformed-once": lambda j, s, c: model_failure(j, s, c, "malformed-once", [{"malformed": True}, {"finish": "九月发票已查询"}], None, 2, [False, True]),
    "breaker": lambda j, s, c: model_failure(j, s, c, "breaker", [FOREIGN] * 6 + [{"finish": "查询完成"}], "TOOL_ERRORS_REPEATED", 3),
    "lost-response": lost_response,
    "never-ran": never_ran,
    "stream-disconnect": stream_disconnect,
}


def summary(meta, items):
    lines = ["# T40 真实 JAR 故障注入检查（自动生成，未经人工修改）", "",
             f"- 生成时间：{meta['generatedAt']}；JAR：`{meta['jar']}`；{meta['java']}",
             "- 做法：真实 JAR + 官方种子全新临时库 + loopback；模型由本机桩服务（Anthropic Messages 协议）按脚本应答，不调用真实模型、不需要密钥。",
             "- 判定只看程序证据：错误码、桩模型收到的请求、数据库（F002 用途与 invoice_operations 台账）、重启后的 /verify。首次 SIGKILL 后给 invoices 加 UPDATE 触发器，任何第二次写入都会失败。", "",
             "| 场景 | 检查 | 结果 | 明细 |", "|---|---|---|---|"]
    lines += [f"| {i['scenario']} | {i['check']} | {'通过' if i['passed'] else '未通过'} | {str(i['detail']).replace('|', '/')} |" for i in items]
    return "\n".join(lines) + "\n"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--jar", default=str(ROOT / "target" / "harness-0.0.1-SNAPSHOT.jar"))
    p.add_argument("--out", default=str(ROOT / "target" / "t40-fault-injection"))
    p.add_argument("--skip", nargs="*", default=[], choices=list(SCENARIOS))
    a = p.parse_args()
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    jar_path = Path(a.jar).resolve()
    java = (subprocess.run(["java", "-version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True).stdout.splitlines() or ["?"])[0]
    meta = {"generatedAt": time.strftime("%Y-%m-%d %H:%M:%S %z"), "jar": jar_path.name, "java": java, "skipped": a.skip}
    stub, checks = Stub(), Checks()
    jar = Jar(jar_path, stub, out)
    try:
        for name, run in SCENARIOS.items():
            if name in a.skip: continue
            try: run(jar, stub, checks)
            except Exception as e:
                checks.add(name, "场景运行完成", False, f"{type(e).__name__}: {e}")
                if jar.process and jar.process.poll() is None: jar.stop(kill=True)
    finally:
        stub.release.set(); stub.server.shutdown()
        (out / "results.json").write_text(json.dumps({"meta": meta, "checks": checks.items}, ensure_ascii=False, indent=2), encoding="utf-8")
        (out / "summary.md").write_text(summary(meta, checks.items), encoding="utf-8")
        print(f"written: {out / 'summary.md'}")
    if not checks.items or not all(i["passed"] for i in checks.items): sys.exit(1)


if __name__ == "__main__":
    main()
