# T39 真实模型大工具结果检查（自动生成，未经人工修改）

- 生成时间：2026-11-20 15:56:15 +0800
- 模型：`qwen3.7-flash`，协议：`anthropic`，端点主机：`dashscope.aliyuncs.com`，JAR：`harness-0.0.1-SNAPSHOT.jar`
- 数据：官方种子 + 经上传入口导入的 60 张合成 C001 2026-10 发票（编号 X001–X060）；全新临时库、loopback。
- 判定只看程序证据与程序已知的确切事实；标 [模型行为] 的项失败说明模型行为而非程序缺陷。

| 检查 | 结果 | 明细 |
|---|---|---|
| 经上传入口导入 60 张合成发票 | 通过 | 导入 {'status': 'IMPORTED', 'invoicesImported': 60, 'bankEntriesImported': 0, 'conflicts': 0, 'skipped': 0, 'errors': 0} |
| 十月查询返回全部 60 行，合计为程序对全部行的计算 | 通过 | 行数 60；total_cents 18187932（应为 18187932） |
| 模型收到的是压缩视图（前 20 行 + 合计 + 带真实 call_id 的取回提示） | 通过 | rows_shown 20；rows_total 60；提示含 call_id True |
| 无错误码的轮次 | 通过 | 出错轮次 — |
| [模型行为] 第 1 轮回复给出 60 张与正确合计 | 通过 | 应含 60 与 ['181,879.32', '181879.32'] |
| [模型行为] 第 2 轮用 recall_context 取回含 X047 的页并答对销售方 | 通过 | 本轮取回页 [['X021', 'X022', 'X023', 'X024', 'X025', 'X026', 'X027', 'X028', 'X029', 'X030', 'X031', 'X032', 'X033', 'X034', 'X035', 'X036', 'X037', 'X038', 'X039', 'X040', 'X041'], ['X042', 'X043', 'X044', 'X045', 'X046', 'X047', 'X048', 'X049', 'X050', 'X051', 'X052', 'X053', 'X054', 'X055', 'X056', 'X057', 'X058', 'X059', 'X060']]；应答 合成销售方47号有限公司 |
| [模型行为] 第 3 轮答出最大金额发票 X053 | 通过 | 本轮取回页数 2，覆盖 ['X021', 'X022', 'X023']…；应答 X053（98,765.43 元） |
| [模型行为] 第 4 轮用途检查成功并答出 12 张 | 通过 | 用途检查 [12] |
| （记录）全程 recall_context 调用 | 通过 | [(2, {'call_id': 'toolu_8cfdec9ae358489d81bfda6c', 'limit': 50, 'offset': 20, 'source': 'TOOL_RESULT'}, None), (2, {'call_id': 'toolu_8cfdec9ae358489d81bfda6c', 'limit': 50, 'offset': 41, 'source': 'TOOL_RESULT'}, None), (3, {'call_id': 'toolu_6547b107f0ff411f8af65bc3', 'limit': 20, 'offset': 20, 'source': 'TOOL_RESULT'}, None), (3, {'call_id': 'toolu_6547b107f0ff411f8af65bc3', 'limit': 20, 'offset': 40, 'source': 'TOOL_RESULT'}, None)] |

| 轮 | 错误码 | 耗时（秒） | 工具 |
|---|---|---|---|
| 1 | — | 6.1 | query_invoices |
| 2 | — | 16.7 | query_invoices, recall_context, recall_context |
| 3 | — | 24.3 | query_invoices, recall_context, recall_context |
| 4 | — | 4.8 | check_invoice_purpose |

逐轮工具参数、模型收到的压缩视图与回复前 600 字见 `results.json`。
