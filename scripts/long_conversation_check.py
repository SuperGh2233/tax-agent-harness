#!/usr/bin/env python3
"""T38 real-model long conversation: one 30-turn task with a standing constraint, a month correction and a
long-term memory, to cover what only offline tests had reached (T34 compression and recall_context, T33 memory
injection).

Every check is judged from program evidence, never from model text: the model input the server built for each
turn (conversation compression, constraints, latest month, memory), and the tool calls the model actually made
(arguments, recall_context results). The task keeps a pending field so it stays open for all turns; each reply
is the model's answer followed by the fixed "task still open" note. Answer wording is recorded, not judged.

C001 has no enabled purpose rule before 2026-09 (R002 1.0 is disabled), so after the correction to August a purpose
check must return UNSUPPORTED_SCOPE (final-round contract A2). Turn 9 asks for exactly that and is checked as an
expected refusal; the memory habit is therefore one that works in any month (look up the applicable rules).

Usage (JDK 21 and LLM_* variables set in the shell; nothing secret is written to the output):
    python3 scripts/long_conversation_check.py --jar target/harness-0.0.1-SNAPSHOT.jar
Output: <out>/results.json, <out>/summary.md and the server log.
"""
import argparse, contextlib, json, os, subprocess, sys, tempfile, time, urllib.parse, urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from innovation_compare import Client, code, free_port, history  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
MEMORY = {"scope": "PERSONAL", "name": "核对习惯", "value": "每次查询发票后，都要同时查询该月适用的规则并注明依据",
          "userRequest": "以后查发票时都顺便查一下适用的规则", "expiresOn": None}
CONSTRAINT_TURN, CORRECTION_TURN, SCOPE_TURN, RECALL_TURN, RECALL_SEQ = 3, 5, 9, 25, 2
TURNS = [
    "先查一下 C001 九月的发票，看看合计金额",                       # 1
    "缺用途的发票有哪些？",                                         # 2 (recalled at turn 25)
    "以后只看发票，不要再查银行流水。",                             # 3 standing constraint
    "F003 的金额是多少？",                                          # 4
    "不对，我要核对的是 2026 年八月，不是九月。请按八月重新查发票。",  # 5 correction
    "八月一共有几张发票？",
    "八月的发票合计金额是多少？",
    "八月里金额最大的是哪一张？",
    "八月有没有缺用途的发票？",                                     # 9 expected UNSUPPORTED_SCOPE
    "适用的用途规则是哪一版？",
    "把八月发票按金额从大到小列前三张。",
    "这几张的销售方分别是谁？",
    "备注为空的发票有几张？",
    "规则里对金额门槛是怎么规定的？",
    "好的，记下了。",
    "再确认一下八月的发票合计。",
    "八月有没有金额超过一千元的发票？",
    "这些发票的开票日期分别是哪天？",
    "继续。",
    "还有别的需要注意的吗？",
    "八月金额最小的是哪一张？",
    "八月的发票里，同一销售方开了不止一张的有哪些？",  # no reason to look at September again
    "用一句话总结目前的核对情况。",
    "好的。",
    f"第 {RECALL_SEQ} 轮我问的是什么？请调取第 {RECALL_SEQ} 轮的原文后回答，不要凭印象。",  # 25 recall
    "八月金额在五千元以上的发票有几张？",
    "这些发票里有没有重复的发票号？",
    "按开票日期从早到晚排一下八月的发票。",
    "好的，谢谢。",
    "最后再查一次当前核对月份的发票合计。",                           # 30
]


def calls(view):
    """Each tool call of the turn with its arguments and outcome."""
    out = []
    for e in history(view):
        call = e.get("call") or {}
        try: result = json.loads(e.get("resultJson") or "null") or {}
        except ValueError: result = {}
        error = (result.get("error") or {}).get("code") if isinstance(result, dict) else None
        try: args = json.loads(call.get("arguments") or "{}")
        except ValueError: args = {"_raw": call.get("arguments")}
        out.append({"name": call.get("name"), "args": args, "error": error})
    return out


def model_input(view):
    text = (view or {}).get("modelInput") or (((view or {}).get("turn") or {}).get("modelInput"))
    try: return text, json.loads(text) if text else None
    except ValueError: return text, None


def converse(base):
    c = Client(base)
    status, session = c.call("GET", "/api/demo/session"); c.csrf = session["csrfToken"]
    status, session = c.call("POST", "/api/demo/session", {"profile": "operator", "enterprise": "C001"})
    if status != 200: sys.exit(f"session failed: HTTP {status}")
    c.csrf = session["csrfToken"]
    status, memories = c.call("POST", "/api/demo/memories", MEMORY)
    if status != 200: sys.exit(f"memory put failed: HTTP {status} {(memories or {}).get('errorCode')}")
    status, task = c.call("POST", "/api/demo/tasks", {"goal": "核对 C001 2026 年九月的发票", "pending": ["F002.purpose"]})
    if status != 200: sys.exit(f"task start failed: HTTP {status} {(task or {}).get('errorCode')}")
    turns = []
    for seq, message in enumerate(TURNS, 1):
        started = time.time()
        status, view = c.call("POST", f"/api/demo/tasks/{task['id']}/turn", {"message": message})
        text, projected = model_input(view)
        conversation = (projected or {}).get("conversation") or {}
        turns.append({
            "seq": seq, "message": message, "http": status, "errorCode": code(view), "seconds": round(time.time() - started, 1),
            "calls": calls(view), "modelInputChars": len(text or ""),
            "turnsTotal": conversation.get("turns_total"), "omittedTurns": (conversation.get("omitted_turns") or {}).get("count", 0),
            "constraintTurns": [x.get("turn") for x in conversation.get("earlier_constraints") or []],
            "latestMonth": ((conversation.get("key_objects") or {}).get("latest_month") or {}).get("value"),
            "memoryInInput": MEMORY["value"] in (text or ""),
            "reply": ((((view or {}).get("turn") or {}).get("result") or {}).get("text") or "")[:300],
        })
        t = turns[-1]
        print(f"#{seq:02d} {t['errorCode'] or 'OK':<22} {t['seconds']:>5}s tools={[x['name'] for x in t['calls']]}", flush=True)
        if t["errorCode"] == "MODEL_NOT_CONFIGURED": sys.exit("model not configured on the server; stopping")
    return turns


def checks(turns):
    """Program-evidence checks; each returns (passed, detail)."""
    after = lambda n: [t for t in turns if t["seq"] > n]
    months = lambda t: [x["args"].get("month") for x in t["calls"] if x["name"] in ("query_invoices", "check_invoice_purpose")]
    scope_refusal = lambda t: t["errorCode"] == "UNSUPPORTED_SCOPE" and all(
        x["name"] == "check_invoice_purpose" and x["args"].get("enterprise_id") == "C001"
        and str(x["args"].get("month", "")) < "2026-09" for x in t["calls"] if x["error"])
    expected = [t["seq"] for t in turns if scope_refusal(t)]
    errors = [t["seq"] for t in turns if t["errorCode"] and not scope_refusal(t)]
    scope = turns[SCOPE_TURN - 1]
    scope_checked = [x for x in scope["calls"] if x["name"] == "check_invoice_purpose" and x["args"].get("month") == "2026-08"]
    wrong_month = [t["seq"] for t in after(CORRECTION_TURN) if "2026-09" in months(t)]
    right_month = [t["seq"] for t in after(CORRECTION_TURN) if "2026-08" in months(t)]
    bank = [t["seq"] for t in after(CONSTRAINT_TURN) if any(x["name"] == "query_bank_entries" for x in t["calls"])]
    recall = [x for x in turns[RECALL_TURN - 1]["calls"] if x["name"] == "recall_context"]
    recall_ok = [x for x in recall if not x["error"] and x["args"].get("source") == "TURN" and x["args"].get("seq") == RECALL_SEQ]
    recall_all = [(t["seq"], x["args"], x["error"]) for t in turns for x in t["calls"] if x["name"] == "recall_context"]
    compressed = [t["seq"] for t in turns if t["omittedTurns"] > 0]
    expect_compressed = [t["seq"] for t in turns if t["seq"] > 5]  # RECENT_TURNS = 4 previous turns verbatim
    constraint_kept = [t["seq"] for t in turns if t["omittedTurns"] >= CORRECTION_TURN
                       and not {CONSTRAINT_TURN, CORRECTION_TURN} <= set(t["constraintTurns"])]
    month_kept = [t["seq"] for t in after(CORRECTION_TURN) if t["latestMonth"] != "八月"]
    memory_missing = [t["seq"] for t in turns if not t["memoryInInput"]]
    habit = [t for t in turns if any(x["name"] == "query_invoices" and not x["error"] for x in t["calls"])]
    habit_missed = [t["seq"] for t in habit if not any(x["name"] == "query_rules" for x in t["calls"])]
    over = [t["seq"] for t in turns if t["modelInputChars"] > 6000]
    return [
        ("除契约预期的拒绝外无错误码", not errors,
         f"意外出错轮次 {errors or '—'}；C001 八月用途检查按契约 A2 返回 UNSUPPORTED_SCOPE 的轮次 {expected or '—'}"),
        ("压缩生效（第 6 轮起有 omitted_turns）", set(expect_compressed) <= set(compressed), f"有压缩的轮次 {compressed[:3]}…共 {len(compressed)} 轮"),
        ("模型输入不超过 6000 字符", not over, f"超限轮次 {over or '—'}；最大 {max(t['modelInputChars'] for t in turns)}"),
        (f"第 {CONSTRAINT_TURN}/{CORRECTION_TURN} 轮限制与更正在折叠后仍保留", not constraint_kept, f"缺失轮次 {constraint_kept or '—'}"),
        ("更正后 latest_month 始终为“八月”", not month_kept, f"不符轮次 {month_kept or '—'}"),
        ("长期记忆每轮都注入模型输入", not memory_missing, f"缺失轮次 {memory_missing or '—'}"),
        ("[模型行为] 更正后查询改用 2026-08、不再用 2026-09", not wrong_month and bool(right_month), f"用九月的轮次 {wrong_month or '—'}；用八月的轮次 {right_month or '—'}"),
        (f"[模型行为] 第 {CONSTRAINT_TURN} 轮后不再查银行流水", not bank, f"仍查流水的轮次 {bank or '—'}"),
        (f"[模型行为] 第 {SCOPE_TURN} 轮实际做八月用途检查，本轮如实以 UNSUPPORTED_SCOPE 结束",
         bool(scope_checked) and all(x["error"] == "UNSUPPORTED_SCOPE" for x in scope_checked) and scope["errorCode"] == "UNSUPPORTED_SCOPE",
         f"用途检查 {[(x['args'], x['error']) for x in scope_checked] or '未调用'}；本轮错误码 {scope['errorCode'] or '无'}"),
        (f"[模型行为] 第 {RECALL_TURN} 轮用 recall_context 成功取回第 {RECALL_SEQ} 轮", bool(recall_ok),
         f"本轮调用 {[(x['args'], x['error']) for x in recall] or '无'}；全程调用 {recall_all or '无'}"),
        ("[模型行为] 查发票的轮次都同时查询适用规则（记忆被使用）", not habit_missed,
         f"查发票 {len(habit)} 轮，未查规则 {habit_missed or '—'}"),
    ]


def summary(turns, results, meta):
    lines = ["# T38 真实模型 30 轮长对话检查（自动生成，未经人工修改）", "",
             f"- 生成时间：{meta['generatedAt']}", f"- 模型：`{meta['model']}`，协议：`{meta['protocol']}`，端点主机：`{meta['endpointHost']}`，JAR：`{meta['jar']}`",
             "- 判定只看程序证据：服务端为每轮构造的模型输入，以及模型实际发出的工具调用与结果；不评判回复措辞。",
             "- 标 [模型行为] 的项反映模型是否利用了上下文，失败说明模型行为而非程序缺陷；其余项是程序行为。", "",
             "| 检查 | 结果 | 明细 |", "|---|---|---|"]
    lines += [f"| {name} | {'通过' if ok else '未通过'} | {detail} |" for name, ok, detail in results]
    lines += ["", "| 轮 | 错误码 | 耗时（秒） | 工具 | 输入字符 | 折叠轮数 | latest_month |", "|---|---|---|---|---|---|---|"]
    lines += [f"| {t['seq']} | {t['errorCode'] or '—'} | {t['seconds']} | {', '.join(x['name'] + ('×' + x['error'] if x['error'] else '') for x in t['calls']) or '—'} "
              f"| {t['modelInputChars']} | {t['omittedTurns']} | {t['latestMonth'] or '—'} |" for t in turns]
    lines += ["", "逐轮工具参数与回复前 300 字见 `results.json`。未覆盖：大工具结果（>6000 字符）裁剪后经 recall_context 分页取回——官方种子数据量不足以触发，仍只有离线测试。"]
    return "\n".join(lines) + "\n"


def require_model():
    for name in ("LLM_ENDPOINT", "LLM_API_KEY", "LLM_MODEL"):
        if not os.environ.get(name): sys.exit(f"{name} is not set; this check needs the real model")


@contextlib.contextmanager
def server(jar, out, prefix):
    """A fresh official-seed database and a loopback server on a free port; yields (base URL, database path)."""
    db = Path(tempfile.mkdtemp(prefix=prefix)) / "demo.db"
    subprocess.run(["java", "-jar", str(jar), "--initialize-data", f"--database={db}"], check=True, stdout=subprocess.DEVNULL)
    port = free_port()
    log = (out / "server.log").open("w")
    process = subprocess.Popen(["java", "-jar", str(jar), f"--taxagent.database={db}", "--server.address=127.0.0.1",
                                f"--server.port={port}"], stdout=log, stderr=subprocess.STDOUT)
    base = f"http://127.0.0.1:{port}"
    try:
        for _ in range(120):
            try:
                with urllib.request.urlopen(base + "/api/health", timeout=2) as r:
                    if r.status == 200: break
            except Exception: time.sleep(0.5)
        else: sys.exit("server not ready; see server.log")
        yield base, db
    finally:
        process.terminate()
        try: process.wait(timeout=20)
        except subprocess.TimeoutExpired: process.kill()
        log.close()


def model_meta(jar, db):
    """What was run, without anything secret: the endpoint is reduced to its host."""
    return {"generatedAt": time.strftime("%Y-%m-%d %H:%M:%S %z"), "model": os.environ["LLM_MODEL"],
            "protocol": os.environ.get("LLM_PROTOCOL", "(by endpoint)"),
            "endpointHost": urllib.parse.urlparse(os.environ["LLM_ENDPOINT"]).hostname, "jar": jar.name, "database": str(db)}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--jar", default=str(ROOT / "target" / "harness-0.0.1-SNAPSHOT.jar"))
    p.add_argument("--out", default=str(ROOT / "target" / "t38-long-conversation"))
    a = p.parse_args()
    require_model()
    jar, out = Path(a.jar).resolve(), Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    with server(jar, out, "t38-") as (base, db):
        turns = converse(base)
    results = checks(turns)
    meta = model_meta(jar, db)
    (out / "results.json").write_text(json.dumps({"meta": meta, "checks": [{"check": n, "passed": ok, "detail": d} for n, ok, d in results],
                                                  "turns": turns}, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "summary.md").write_text(summary(turns, results, meta), encoding="utf-8")
    for name, ok, detail in results: print(f"{'PASS' if ok else 'FAIL'}  {name}  {detail}")
    print(f"written: {out / 'summary.md'}")


if __name__ == "__main__":
    main()
