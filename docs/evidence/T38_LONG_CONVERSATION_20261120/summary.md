# T38 真实模型 30 轮长对话检查（自动生成，未经人工修改）

- 生成时间：2026-11-20 12:13:10 +0800
- 模型：`qwen3.7-flash`，协议：`anthropic`，端点主机：`dashscope.aliyuncs.com`，JAR：`harness-0.0.1-SNAPSHOT.jar`
- 判定只看程序证据：服务端为每轮构造的模型输入，以及模型实际发出的工具调用与结果；不评判回复措辞。
- 标 [模型行为] 的项反映模型是否利用了上下文，失败说明模型行为而非程序缺陷；其余项是程序行为。

| 检查 | 结果 | 明细 |
|---|---|---|
| 除契约预期的拒绝外无错误码 | 未通过 | 意外出错轮次 [21]；C001 八月用途检查按契约 A2 返回 UNSUPPORTED_SCOPE 的轮次 [5, 9, 15] |
| 压缩生效（第 6 轮起有 omitted_turns） | 通过 | 有压缩的轮次 [6, 7, 8]…共 25 轮 |
| 模型输入不超过 6000 字符 | 通过 | 超限轮次 —；最大 1617 |
| 第 3/5 轮限制与更正在折叠后仍保留 | 通过 | 缺失轮次 — |
| 更正后 latest_month 始终为“八月” | 通过 | 不符轮次 — |
| 长期记忆每轮都注入模型输入 | 通过 | 缺失轮次 — |
| [模型行为] 更正后查询改用 2026-08、不再用 2026-09 | 未通过 | 用九月的轮次 [19]；用八月的轮次 [6, 7, 8, 9, 11, 12, 13, 15, 16, 17, 18, 19, 20, 21, 22, 26, 27, 28, 29, 30] |
| [模型行为] 第 3 轮后不再查银行流水 | 通过 | 仍查流水的轮次 — |
| [模型行为] 第 9 轮实际做八月用途检查，本轮如实以 UNSUPPORTED_SCOPE 结束 | 通过 | 用途检查 [({'enterprise_id': 'C001', 'month': '2026-08'}, 'UNSUPPORTED_SCOPE')]；本轮错误码 UNSUPPORTED_SCOPE |
| [模型行为] 第 25 轮用 recall_context 成功取回第 2 轮 | 通过 | 本轮调用 [({'seq': 2, 'source': 'TURN'}, None)]；全程调用 [(21, {'call_id': 'query_invoices_C001_2026-08', 'limit': 5, 'source': 'TOOL_RESULT'}, 'NOT_FOUND'), (25, {'seq': 2, 'source': 'TURN'}, None)] |
| [模型行为] 查发票的轮次都同时查询适用规则（记忆被使用） | 未通过 | 查发票 24 轮，未查规则 [7, 9, 11, 17, 18, 21, 28, 30] |

| 轮 | 错误码 | 耗时（秒） | 工具 | 输入字符 | 折叠轮数 | latest_month |
|---|---|---|---|---|---|---|
| 1 | — | 29.7 | query_invoices, query_rules | 324 | 0 | — |
| 2 | — | 12.9 | query_invoices, query_rules | 828 | 0 | 九月 |
| 3 | — | 6.2 | — | 955 | 0 | 九月 |
| 4 | — | 19.9 | query_invoices, query_rules | 1000 | 0 | 九月 |
| 5 | UNSUPPORTED_SCOPE | 17.6 | query_invoices, query_rules, check_invoice_purpose×UNSUPPORTED_SCOPE | 1224 | 0 | 九月 |
| 6 | — | 10.0 | query_invoices, query_rules | 1408 | 1 | 八月 |
| 7 | — | 8.6 | query_invoices | 1410 | 2 | 八月 |
| 8 | — | 22.5 | query_invoices, query_rules | 1428 | 3 | 八月 |
| 9 | UNSUPPORTED_SCOPE | 20.5 | check_invoice_purpose×UNSUPPORTED_SCOPE, query_invoices | 1472 | 4 | 八月 |
| 10 | — | 19.0 | query_rules | 1435 | 5 | 八月 |
| 11 | — | 9.6 | query_invoices | 1463 | 6 | 八月 |
| 12 | — | 26.8 | query_invoices, query_rules | 1477 | 7 | 八月 |
| 13 | — | 22.3 | query_invoices, query_rules | 1520 | 8 | 八月 |
| 14 | — | 21.1 | query_rules | 1452 | 9 | 八月 |
| 15 | UNSUPPORTED_SCOPE | 61.0 | query_invoices, query_rules, check_invoice_purpose×UNSUPPORTED_SCOPE | 1410 | 10 | 八月 |
| 16 | — | 15.9 | query_invoices, query_rules | 1488 | 11 | 八月 |
| 17 | — | 20.6 | query_invoices | 1545 | 12 | 八月 |
| 18 | — | 7.0 | query_invoices | 1510 | 13 | 八月 |
| 19 | — | 24.3 | query_invoices, query_rules, query_invoices | 1510 | 14 | 八月 |
| 20 | — | 32.0 | query_invoices, query_rules | 1527 | 15 | 八月 |
| 21 | NOT_FOUND | 17.7 | query_invoices, recall_context×NOT_FOUND | 1479 | 16 | 八月 |
| 22 | — | 17.2 | query_invoices, query_rules | 1366 | 17 | 八月 |
| 23 | — | 7.4 | — | 1506 | 18 | 八月 |
| 24 | — | 13.0 | — | 1429 | 19 | 八月 |
| 25 | — | 4.9 | recall_context | 1454 | 20 | 八月 |
| 26 | — | 17.4 | query_invoices, query_rules | 1541 | 21 | 八月 |
| 27 | — | 24.6 | query_invoices, query_rules | 1572 | 22 | 八月 |
| 28 | — | 12.8 | query_invoices | 1573 | 23 | 八月 |
| 29 | — | 29.1 | query_invoices, query_rules | 1546 | 24 | 八月 |
| 30 | — | 5.7 | query_invoices | 1617 | 25 | 八月 |

逐轮工具参数与回复前 300 字见 `results.json`。未覆盖：大工具结果（>6000 字符）裁剪后经 recall_context 分页取回——官方种子数据量不足以触发，仍只有离线测试。
