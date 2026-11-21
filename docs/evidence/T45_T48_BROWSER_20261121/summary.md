# T45–T48 浏览器点验（2026-11-21）

- 对象：提交 `05bfcab` 打包出的 `target/harness-0.0.1-SNAPSHOT.jar`（`frontend/dist` 于 21:49 由 `npm run verify` 构建，打包时复制进 `static/react-app`）
- 环境：macOS 26.6.2 arm64、Temurin 21.0.12.1、真实 Chrome（Playwright 驱动，`channel=chrome`）
- 服务：`127.0.0.1:18086`，全新库 `/tmp/t48ui/harness.db`（`--initialize-data` 播种官方 v1.1.0）
- 模型：`LLM_MODEL=qwen3.8-flash`，`LLM_VISION_*` 同档
- 数据全部合成：陌生表头工作簿（票号/日期/开票单位/品名/含税金额/附注，行 F8201–F8202）、无文本层扫描件 PDF（F9002 / 2468.10）、批量对象为种子里的 F004、F006
- 判定以**接口响应 + 数据库行**为准，截图仅作界面观感佐证

## 一、列对应（T46）

| 步骤 | 结果 | 证据 |
|---|---|---|
| 上传陌生表头工作簿 | 通过 | 响应 `status=IMPORTED`、`tables` 返回 1 个候选；界面提示「另有 1 个表格未能识别列，可在下方指定列对应后导入」 |
| 映射面板渲染 | 通过 | 标题「这些表格的列需要你来对应」，逐列下拉按建议预填（发票编号\*/开票日期\*/销售方名称\*/项目名称\*/价税合计（元）\*/不导入），并带样例行 — `01-column-mapping-form.png` |
| 勾选确认后按映射导入 | 通过 | 响应 `status=IMPORTED`、`invoicesImported=2`、`errors=[]` — `02-column-mapping-imported.png` |
| 数据库落库 | 通过 | F8201=6,450 分、F8202=3,000 分 |
| 映射留痕 | 通过 | `file_column_mappings` 1 行：`票据 / INVOICE / header_row=1 / {ID:0, DATE:1, SELLER:2, ITEM:3, AMOUNT:4} / U001` |

## 二、扫描件确认（T48）

| 步骤 | 结果 | 证据 |
|---|---|---|
| 上传无文本层 PDF 并识别 | 通过 | 草稿面板出现；票面图与字段并排 — `03-scan-draft.png` |
| 票面图渲染（CSP `img-src data:`） | 通过 | `<img>` `complete=true`、`naturalWidth=1275`、`src` 前缀 `data:image/png;base64,`（若被 CSP 拦截则为 0） |
| 模型字段 | 通过 | F9002 / 2026-10-21 / 合成供应商甲公司 / 办公用品（合成） / 2468.10；大写「贰仟肆佰陆拾捌元壹角整」 |
| 程序核对金额 | 通过 | 界面显示「大写金额与小写金额一致」 |
| 再次输入价税合计 → 确认导入 | 通过 | 未填时按钮禁用；填入 2468.10 并勾选后可点，结果 `IMPORTED`、`amountCents=246810`、`differences=[]` — `04-scan-imported.png` |
| 数据库 | 通过 | F9002 落库（amount_cents=246810，日期/销售方/备注均一致）；`scan_confirmations` 2 行（F9001、F9002） |

## 三、批量保存（T47）

| 步骤 | 结果 | 证据 |
|---|---|---|
| 新建任务时挂待补充字段 | 通过 | 用界面「添加待补充字段」填 `F004.purpose,F006.purpose`，任务保持「等待补充」，审核页可写 |
| 查询轮（真模型） | 通过 | qwen3.8-flash 答复「9 月 10 张发票 962,206 分，其中 5 张缺用途，规则 R002 v2.0」，并列出 5 张明细 |
| 批量面板 | 通过 | 「批量核对精确修改」，列出本轮已查询的 10 张发票及其数据库当前值 — `05-batch-filled.png` |
| 勾选 2 张并填拟修改值 | 通过 | F004、F006 均填「差旅与办公（合成）」；未勾选确认时「继续二次确认」禁用 |
| 二次确认弹窗 | 通过 | 显示发票/字段/当前值/拟修改值 + 批次 operationId，按钮「确认，现在批量保存」 — `06-batch-confirm.png` |
| 整批结果 | 通过 | 页面「批量 verified=true · 2 张发票已逐张回读核验」，逐张「无需复核」 — `07-batch-verified.png` |
| 数据库 | 通过 | C001/F004、C001/F006 用途已写入；C002 同名发票未受影响（企业隔离） |
| 审计每张一行 | 通过 | `demo_audit` 2 行 `update_invoice`，各自独立 operationId（F004→`9ded7a03…`、F006→`44878720…`），`verified=1`；`invoice_operations` 2 条 |

## 过程中值得记录的两点

- **首次上传看起来「没有映射面板」，实为服务的是旧前端产物**：`target/classes/static/react-app` 里还是 2026-11-20 的 bundle。用当前 `frontend/dist` 重新 `mvn -Dfrontend.skip=true package` 后，面板正常出现。发布前务必确认打包用的是最新 `dist`。
- **只查询不挂待补充字段的任务会立刻 COMPLETED，审核页按设计禁用写入**。要在界面里做批量保存，需先用「添加待补充字段」让任务保持打开 —— 与 `scripts/` 下真实模型脚本的做法一致。
