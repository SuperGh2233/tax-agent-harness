# T47/T48 真实模型两项检查（自动生成，未经人工修改）

- 生成时间：2026-11-21 22:17 +0800
- 模型：`qwen3.7-flash`（对话与读图同一档，用户 2026-11-21 指定「暂时先用 3.7 flash 测试」），协议 `anthropic`，端点主机 `dashscope.aliyuncs.com`
- 环境：Temurin 21.0.12.1；全新临时库 `/tmp/t48check/harness.db`（由 `--initialize-data` 播种官方 v1.1.0 种子）；loopback `127.0.0.1:18084`；未触碰仓库内 `data/harness.db`
- 数据全部合成：发票为虚构的「F9001 / 合成供应商甲公司 / 1234.56 元」，批量对象为官方种子里的 F002、F003
- 判定只看程序证据（HTTP 响应、`BatchUpdateResult`、数据库行），不看模型措辞；密钥与端点凭证未写入本目录

## 一、T48 扫描件识别（`LLM_VISION_*` 已配置）

输入为**无文本层**的纯图片 PDF（66207 字节，先由 HTML 渲染成 PNG 再转 PDF，`pdftotext` 抽取 0 字符），以此确保走读图分支而非文字型 PDF 分支。模型确实能读图：单独探测时已正确读出票面三个字段。

| 检查 | 结果 | 明细 |
|---|---|---|
| 草稿状态 | 通过 | `status=DRAFT`，`errorCode=null`，耗时 4.9 s |
| 发票号码 / 开票日期 | 通过 | `F9001` / `2026-10-20`（与合成票面一致） |
| 销售方 / 项目 | 通过 | `合成供应商甲公司` / `办公用品 (合成)` |
| 小写价税合计 | 通过 | `1234.56` |
| 大写金额照抄 | 通过 | `壹仟贰佰叁拾肆元伍角陆分` |
| 程序核对大小写金额 | 通过 | `checks=[AMOUNT_WORDS_MATCH]`，`unreadable=[]` |
| 逐项确认后导入 | 通过 | `status=IMPORTED`，`invoiceId=F9001`，`amountCents=123456`，`differences=[]`（草稿与确认值无差异） |
| 票面图片回传 | 通过 | `pageImage` 为 `data:image/png;base64,…`（未存入本目录） |

## 二、T47 批量保存（`LLM_MODEL=qwen3.7-flash`）

流程与前端审核页一致：先一轮真模型查询，再发结构化批量批准（每张自带 operationId）。

| 检查 | 结果 | 明细 |
|---|---|---|
| 查询轮 | 通过 | HTTP 200，11.7 s，无错误码；模型调用 `check_invoice_purpose`, `query_invoices` |
| 保存轮 | 通过 | HTTP 200，11.6 s，无错误码；模型调用 `update_invoice` |
| 整批事务结果 | 通过 | `verified=true`，`results` 2 条，逐张 `verified=true` |
| 程序复核清单 | 通过 | `review=[]`（无需人工复核） |
| 数据库落库 | 通过 | F002、F003 的 `purpose` 均为 `办公用品（合成）` |
| 审计每张一行 | 通过 | `demo_audit` 中 2 行 `update_invoice`，各自独立 operationId：F002→`23ec9106…`、F003→`c8846ca2…`，`verified=1` |
| 程序给出的结论文案 | 记录 | 「批量 2 张发票在同一事务内保存，逐张 verified=true 且数据库回读已核验；程序检查无需复核」 |

## 说明与限制

- 本轮用 `qwen3.7-flash` 验证**流程可用**；比赛采用 `qwen3.8`，只需改 `LLM_MODEL` 后重跑同一脚本，代码与规则无需改动。
- 批量只覆盖 2 张（最小批）且都是补空 `purpose`；「覆盖非空值」「被清空」「部分失败全量回滚」等复核分支仍由离线用例 `BatchSaveTest` 覆盖。
- 明细 JSON 见同目录 `scan.json`、`batch.json`。
