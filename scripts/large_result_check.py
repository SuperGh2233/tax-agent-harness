#!/usr/bin/env python3
"""T39 real-model large tool result: the one T34 path that only offline tests had reached. The official seed is too
small to produce a tool result over 6000 characters, so this check first imports 60 synthetic C001 invoices for
2026-10 through the ordinary upload endpoint (T29; new keys only, nothing existing is touched), then asks questions
whose answers lie beyond the 20 rows the model is shown.

Program evidence: the upload count, the full query result and its program total, and the compacted view the model
actually received (modelJson: rows_shown, rows_total, the recall hint with the real call_id). Model behaviour, marked
[模型行为]: whether it paged the stored result with recall_context and answered from it. Replies are recorded; the
only text checks are exact facts the program knows (an invoice id, a seller, a count, a total).

Usage (JDK 21 and LLM_* variables set in the shell; nothing secret is written to the output):
    python3 scripts/large_result_check.py --jar target/harness-0.0.1-SNAPSHOT.jar
Output: <out>/results.json, <out>/summary.md and the server log.
"""
import argparse, io, json, sys, time, urllib.error, urllib.request, uuid, zipfile
from pathlib import Path
from xml.sax.saxutils import escape

sys.path.insert(0, str(Path(__file__).resolve().parent))
from innovation_compare import Client, code, history  # noqa: E402
from long_conversation_check import model_meta, require_model, server  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
COUNT, SHOWN, TARGET, LARGEST = 60, 20, 47, 53
HEADERS = ["企业编号", "发票编号", "开票日期", "销售方名称", "项目名称", "价税合计（元）", "用途", "备注"]


def invoices():
    """Synthetic rows; ids and dates both ascend, so row n of the result (ORDER BY date, id) is X0nn."""
    rows = []
    for i in range(1, COUNT + 1):
        cents = 9876543 if i == LARGEST else 100000 + i * 1357
        rows.append({"id": f"X{i:03d}", "date": f"2026-10-{1 + (i - 1) * 27 // (COUNT - 1):02d}",
                     "seller": f"合成销售方{i:02d}号有限公司", "item": f"较长的合成票面项目名称用于触发结果压缩（第{i}张）",
                     "cents": cents, "purpose": "" if i % 5 == 0 else "办公用品采购", "remarks": "T39 合成数据"})
    return rows


def xlsx(rows):
    """A minimal workbook with inline strings, written with the standard library only."""
    def cell(column, number, value):
        return f'<c r="{column}{number}" t="inlineStr"><is><t>{escape(value)}</t></is></c>'
    lines = [[*HEADERS]] + [["C001", r["id"], r["date"], r["seller"], r["item"], f"{r['cents'] / 100:.2f}", r["purpose"], r["remarks"]]
                            for r in rows]
    data = "".join(f'<row r="{n}">' + "".join(cell("ABCDEFGH"[k], n, v) for k, v in enumerate(line) if v != "") + "</row>"
                   for n, line in enumerate(lines, 1))
    head = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    parts = {
        "[Content_Types].xml": head + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
            '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
            '</Types>',
        "_rels/.rels": head + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            f'<Relationship Id="rId1" Type="{rel}/officeDocument" Target="xl/workbook.xml"/></Relationships>',
        "xl/workbook.xml": head + f'<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="{rel}">'
            '<sheets><sheet name="发票" sheetId="1" r:id="rId1"/></sheets></workbook>',
        "xl/_rels/workbook.xml.rels": head + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            f'<Relationship Id="rId1" Type="{rel}/worksheet" Target="worksheets/sheet1.xml"/></Relationships>',
        "xl/worksheets/sheet1.xml": head + '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
            f"<sheetData>{data}</sheetData></worksheet>",
    }
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as z:
        for name, text in parts.items(): z.writestr(name, text)
    return buffer.getvalue()


def upload(c, name, content):
    boundary = uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
            "Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n").encode() \
        + content + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(c.base + "/api/demo/files", data=body, method="POST")
    request.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    request.add_header("X-CSRF-Token", c.csrf)
    try:
        with c.http.open(request, timeout=120) as response: return response.status, json.loads(response.read() or b"null")
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read() or b"null")
        except ValueError: return e.code, None


def exchanges(view):
    """Each tool call of the turn: arguments, full result, and what the model was shown when it differed."""
    out = []
    for e in history(view):
        call = e.get("call") or {}
        def parse(text):
            try: return json.loads(text) if text else None
            except ValueError: return None
        result, shown = parse(e.get("resultJson")), parse(e.get("modelJson"))
        out.append({"id": call.get("id"), "name": call.get("name"), "args": parse(call.get("arguments")) or {},
                    "error": ((result or {}).get("error") or {}).get("code"), "data": (result or {}).get("data"),
                    "shown": (shown or {}).get("data") if shown else None})
    return out


TURNS = [
    "查询 C001 2026 年 10 月的发票，告诉我一共几张、合计金额是多少。",
    f"十月的发票里，X{TARGET:03d} 的销售方是谁？金额多少？",
    "十月金额最大的是哪一张发票？请核对全部明细后回答，不要只看前几行。",
    "十月有多少张发票没有填写用途？",
]


def converse(base, rows):
    c = Client(base)
    status, session = c.call("GET", "/api/demo/session"); c.csrf = session["csrfToken"]
    status, session = c.call("POST", "/api/demo/session", {"profile": "operator", "enterprise": "C001"})
    if status != 200: sys.exit(f"session failed: HTTP {status}")
    c.csrf = session["csrfToken"]
    status, uploaded = upload(c, "T39合成十月发票.xlsx", xlsx(rows))
    if status != 200: sys.exit(f"upload failed: HTTP {status} {json.dumps(uploaded, ensure_ascii=False)[:500]}")
    status, task = c.call("POST", "/api/demo/tasks", {"goal": "核对 C001 2026 年 10 月导入的发票", "pending": ["F002.purpose"]})
    if status != 200: sys.exit(f"task start failed: HTTP {status} {(task or {}).get('errorCode')}")
    turns = []
    for seq, message in enumerate(TURNS, 1):
        started = time.time()
        status, view = c.call("POST", f"/api/demo/tasks/{task['id']}/turn", {"message": message})
        turns.append({"seq": seq, "message": message, "http": status, "errorCode": code(view),
                      "seconds": round(time.time() - started, 1), "exchanges": exchanges(view),
                      "reply": ((((view or {}).get("turn") or {}).get("result") or {}).get("text") or "")[:600]})
        t = turns[-1]
        print(f"#{seq} {t['errorCode'] or 'OK':<22} {t['seconds']:>5}s tools={[x['name'] for x in t['exchanges']]}", flush=True)
        if t["errorCode"] == "MODEL_NOT_CONFIGURED": sys.exit("model not configured on the server; stopping")
    upload_summary = {k: uploaded.get(k) for k in ("status", "invoicesImported", "bankEntriesImported")}
    upload_summary.update({k: len(uploaded.get(k) or []) for k in ("conflicts", "skipped", "errors")})
    return upload_summary, turns


def yuan(cents):
    return {f"{cents / 100:,.2f}", f"{cents / 100:.2f}"}


def recalled_rows(exchange):
    """Invoice ids on a recall_context page, or [] when the call failed or was not a TOOL_RESULT page."""
    if exchange["name"] != "recall_context" or exchange["error"] or not exchange["data"]: return []
    try: page = json.loads(exchange["data"].get("content") or "null") or {}
    except ValueError: return []
    return [r.get("key", {}).get("invoice_id") for r in (page.get("data") or {}).get("rows") or []]


def checks(upload_summary, turns, rows):
    total = sum(r["cents"] for r in rows)
    target, largest = rows[TARGET - 1], rows[LARGEST - 1]
    missing = sum(1 for r in rows if not r["purpose"])
    first_query = next((x for t in turns for x in t["exchanges"] if x["name"] == "query_invoices"
                        and x["args"].get("month") == "2026-10" and not x["error"]), None)
    full = (first_query or {}).get("data") or {}
    shown = (first_query or {}).get("shown") or {}
    errors = [t["seq"] for t in turns if t["errorCode"]]
    pages = {t["seq"]: [ids for x in t["exchanges"] if (ids := recalled_rows(x))] for t in turns}
    recall_all = [(t["seq"], x["args"], x["error"]) for t in turns for x in t["exchanges"] if x["name"] == "recall_context"]
    t1, t2, t3, t4 = turns
    purpose = [x for x in t4["exchanges"] if x["name"] == "check_invoice_purpose" and not x["error"]]
    return [
        ("经上传入口导入 60 张合成发票", upload_summary.get("invoicesImported") == COUNT and not upload_summary.get("errors"),
         f"导入 {upload_summary}"),
        ("十月查询返回全部 60 行，合计为程序对全部行的计算", len(full.get("rows") or []) == COUNT and full.get("total_cents") == total,
         f"行数 {len(full.get('rows') or [])}；total_cents {full.get('total_cents')}（应为 {total}）"),
        ("模型收到的是压缩视图（前 20 行 + 合计 + 带真实 call_id 的取回提示）",
         bool(shown) and shown.get("rows_shown") == SHOWN and shown.get("rows_total") == COUNT
         and shown.get("total_cents") == total and (first_query or {}).get("id", "\0") in (shown.get("note") or ""),
         f"rows_shown {shown.get('rows_shown')}；rows_total {shown.get('rows_total')}；提示含 call_id {bool(first_query) and first_query['id'] in (shown.get('note') or '')}"),
        ("无错误码的轮次", not errors, f"出错轮次 {errors or '—'}"),
        ("[模型行为] 第 1 轮回复给出 60 张与正确合计", "60" in t1["reply"] and any(y in t1["reply"] for y in yuan(total)),
         f"应含 60 与 {sorted(yuan(total))}"),
        (f"[模型行为] 第 2 轮用 recall_context 取回含 X{TARGET:03d} 的页并答对销售方",
         any(target["id"] in ids for ids in pages[2]) and target["seller"] in t2["reply"],
         f"本轮取回页 {pages[2] or '无'}；应答 {target['seller']}"),
        (f"[模型行为] 第 3 轮答出最大金额发票 X{LARGEST:03d}", largest["id"] in t3["reply"],
         f"本轮取回页数 {len(pages[3])}，覆盖 {sorted({i for ids in pages[3] for i in ids})[:3]}…；应答 {largest['id']}（{sorted(yuan(largest['cents']))[0]} 元）"),
        (f"[模型行为] 第 4 轮用途检查成功并答出 {missing} 张", bool(purpose) and str(missing) in t4["reply"],
         f"用途检查 {[len((x['data'] or {}).get('missing') or []) for x in purpose] or '未调用'}"),
        ("（记录）全程 recall_context 调用", True, f"{recall_all or '无'}"),
    ]


def summary(upload_summary, turns, results, meta):
    lines = ["# T39 真实模型大工具结果检查（自动生成，未经人工修改）", "",
             f"- 生成时间：{meta['generatedAt']}", f"- 模型：`{meta['model']}`，协议：`{meta['protocol']}`，端点主机：`{meta['endpointHost']}`，JAR：`{meta['jar']}`",
             f"- 数据：官方种子 + 经上传入口导入的 {COUNT} 张合成 C001 2026-10 发票（编号 X001–X{COUNT:03d}）；全新临时库、loopback。",
             "- 判定只看程序证据与程序已知的确切事实；标 [模型行为] 的项失败说明模型行为而非程序缺陷。", "",
             "| 检查 | 结果 | 明细 |", "|---|---|---|"]
    lines += [f"| {name} | {'通过' if ok else '未通过'} | {detail} |" for name, ok, detail in results]
    lines += ["", "| 轮 | 错误码 | 耗时（秒） | 工具 |", "|---|---|---|---|"]
    lines += [f"| {t['seq']} | {t['errorCode'] or '—'} | {t['seconds']} | "
              f"{', '.join(x['name'] + ('×' + x['error'] if x['error'] else '') for x in t['exchanges']) or '—'} |" for t in turns]
    lines += ["", "逐轮工具参数、模型收到的压缩视图与回复前 600 字见 `results.json`。"]
    return "\n".join(lines) + "\n"


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--jar", default=str(ROOT / "target" / "harness-0.0.1-SNAPSHOT.jar"))
    p.add_argument("--out", default=str(ROOT / "target" / "t39-large-result"))
    a = p.parse_args()
    require_model()
    jar, out = Path(a.jar).resolve(), Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    rows = invoices()
    with server(jar, out, "t39-") as (base, db):
        upload_summary, turns = converse(base, rows)
    results = checks(upload_summary, turns, rows)
    meta = model_meta(jar, db)
    for t in turns:  # The full 60-row results are reproducible from the synthetic data; keep the evidence readable.
        for x in t["exchanges"]:
            if x["name"] == "query_invoices" and x["data"]:
                x["data"] = {"rows_count": len(x["data"].get("rows") or []), "total_cents": x["data"].get("total_cents")}
    (out / "results.json").write_text(json.dumps({"meta": meta, "upload": upload_summary,
                                                  "checks": [{"check": n, "passed": ok, "detail": d} for n, ok, d in results],
                                                  "turns": turns}, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "summary.md").write_text(summary(upload_summary, turns, results, meta), encoding="utf-8")
    for name, ok, detail in results: print(f"{'PASS' if ok else 'FAIL'}  {name}  {detail}")
    print(f"written: {out / 'summary.md'}")


if __name__ == "__main__":
    main()
