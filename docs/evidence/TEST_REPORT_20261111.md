# 提交前测试报告（2026-11-11）

> 本报告只记录**实际执行并观察到结果**的项。未执行项标"未做"，不做推断。
> 全部测试在 loopback 服务 + 独立临时 SQLite + 官方合成数据上完成；未访问 `data/harness.db`、未使用真实客户数据。

## 0. 结论

- 主链在真实模型（`qwen3.7-flash`，DashScope Anthropic 兼容端点）下**完整跑通**：只读查询 → 待补追问 → 跨轮续接 → 受控保存 → `verified=true` 回读 → 审计留痕。
- 浏览器内**受控保存此前实际不可用**（发票行日期被序列化成 epoch 数组，前端拒收 → 审核页永远拿不到发票）。本轮修复并加了回归测试，修复后浏览器端保存闭环通过。详见第 5 节。
- Java 248（本轮结束时计数；T19 追加 4 项后为 **252**）/ 前端 37 / OSV 0 未抑制 / 密钥扫描干净 / `git diff --check` 通过。

## 0.1 数字口径（先读这条，否则会以为数据自相矛盾）

本报告第 3 节（API 保存 `F002`）与第 6 节（浏览器查询与保存 `F009`）跑在**同一个临时库** `/tmp/taxagent-test-AXRSwm/test.db` 上，写入是按顺序累积的：

| 时点 | C001 2026-09 缺用途 | 台账 | 出现在 |
|---|---|---|---|
| 新建库（未写入） | **5 张**：F002/F004/F003/F006/F009 | 0 行 | 基准口径 |
| 第 3 节 API 保存 F002 之后 | **4 张**：F004/F003/F006/F009 | 1 行 | 第 6 节"只读查询" |
| 第 6 节浏览器保存 F009 之后 | **3 张**：F004/F003/F006 | 2 行 | 第 6 节末尾核对 |

所以"5 张"和"4 张"都对，差别只在库被写到哪一步。`check_invoice_purpose` 返回的是**库里当时的全量**缺用途项，不做任何过滤。录制演示与写结论时，必须标明数字对应的时点（脚本见 [DEMO_SCRIPT.md](../../DEMO_SCRIPT.md) 第三节口径表）。

## 1. 环境

| 项目 | 值 |
|---|---|
| 主机 | macOS（darwin），16 GB RAM |
| JDK | Temurin 21.0.12.1+1 |
| Maven | 3.10.x，仓库 `.m2/repository`（离线 `-o`） |
| Node | 22.22.2（managed），npm 脚本 `verify` |
| 服务 | `--server.address=127.0.0.1 --server.port=18085` |
| 数据库 | `/tmp/taxagent-test-AXRSwm/test.db`（`--initialize-data` 新建） |
| 模型 | `LLM_MODEL=qwen3.7-flash`，endpoint 路径含 `/anthropic` → AnthropicMessagesClient；`LLM_PROTOCOL` 未设，超时 60s |
| 密钥 | 仅本机 `.env` 注入进程环境，未打印、未入库 |

构建方式（macOS 可复现）：`cd frontend && npm run build` → `mvn -DskipTests -Dfrontend.skip=true clean package`（`copy-react-build` 仍会把 `frontend/dist` 打进 `static/react-app`）。产物 `target/harness-0.0.1-SNAPSHOT.jar`，33,470,771 bytes。

## 2. 自动化回归

| 项目 | 命令 | 结果 |
|---|---|---|
| Java 全量 | `mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test` | **248 / 0 / 0 / 0，BUILD SUCCESS**（含本轮新增 1 项；T19 追加 4 项后最终为 **252/0/0/0**） |
| 前端校验 | `npm run verify`（typecheck + lint + Vitest + Vite build） | **37 / 37 通过**，1636 modules，dist 22.91 kB CSS + 294.97 kB JS |
| 依赖漏洞 | `python3 scripts/osv_scan.py`（35 个 runtime 依赖） | **0 未抑制**，2 条 Spring 6.2.19 风险接受（沿用既有豁免） |
| 密钥扫描 | `python3 scripts/secret_scan.py` | `SECRET_SCAN_CLEAN` |
| 空白检查 | `git diff --check` | 通过 |

## 3. API 层受控保存（任务 `1dc188aa`）

| 步骤 | 观察结果 |
|---|---|
| 身份 | `operator / U001 / C001` |
| 批准对象 | `operationId=cd15f309-ecf8-4561-b04e-6b62af12b9b8`，`invoiceId=F002`，`changes={purpose}`，`confirmed=true` |
| 本轮耗时 | 29.2 s（HTTP 200） |
| 工具序列 | `update_invoice` → `query_invoices` → `check_invoice_purpose` |
| 门控返回 | `errorCode=null`，`message=实际工具 verified=true 且数据库回读已核验` |
| verification | `verified=true`，`key={C001,F002}`，`purpose=办公用品采购（合成测试）` |
| 台账 | `invoice_operations` **新增 1 行**，含 `task_id` 外键、`user_id=U001`、`payload` 精确 JSON |
| 审计 | `tool=update_invoice, outcome=TOOL_RESULT, verified=1, fields=purpose`，**不含金额与用途明文** |
| 幂等重放 | 同 `requestId` 同载荷 → 返回同一视图，台账仍 1 行，未二次写入 |
| 篡改重放 | 同 `requestId` 改载荷 → **HTTP 409 CONFLICT**，无写入 |

## 4. 负例验证

| 场景 | 结果 |
|---|---|
| viewer 访问 operator 的任务 | HTTP 404 NOT_FOUND（任务隔离，不泄漏存在性） |
| viewer 在自己任务上携带写批准 | **HTTP 403 PERMISSION_DENIED**（仅 operator 可批准） |
| operator 口头要求"直接保存"但无批准对象 | 模型只调用 `query_invoices`/`check_invoice_purpose`，**未发写工具**；任务以只读完成；F003 仍为空，台账仍 1 行 |

## 5. 本轮发现并修复的缺陷（阻塞提交门禁）

**现象**：浏览器进入"审核与结果"后，发票下拉恒为空，页面提示"本轮没有发票查询证据"，`继续二次确认` 永久禁用 → 浏览器内受控保存无法完成。

**根因**：`AgentRuntime` 序列化工具结果用的 `ObjectMapper` 只做了 `findAndRegisterModules()`，未关闭 `WRITE_DATES_AS_TIMESTAMPS`，`LocalDate` 被写成 `[2026,9,8]`。前端 `isInvoice()` 要求 `invoice_date` 为字符串，故整批行被丢弃。副作用：模型读到的工具结果也是 `[2026,9,8]`，日期可读性差。

**修复**（2 个文件）：

- `src/main/java/com/taxagent/harness/runtime/AgentRuntime.java`：`JSON` 增加 `.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)`，日期输出 ISO 字符串。
- `src/test/java/com/taxagent/harness/AgentRuntimeTest.java`：新增 `invoiceRowsExposeIsoDateStringsNotArrays`，断言 10 行发票的 `invoice_date` 为 `\d{4}-\d{2}-\d{2}` 文本、`amount_cents` 为整数。

**修复前后对照**：修复前审核页发票下拉只有占位项（截图 `ui-e2e-20261111-02-review-list.png` 为修复后，可见 10 张发票）；修复后 `AgentRuntimeTest` 25 项、全量 248 项通过。

## 6. 浏览器 E2E（真实 Chromium，非 headless stub）

| 步骤 | 观察结果 | 截图 |
|---|---|---|
| 只读查询 | `operator/C001` 建任务，模型引用 **R002 (v2.0)**，列出 4 张缺用途发票（F004/F003/F006/F009），2 个工具调用，任务已结束。**该库此刻已被第 3 节写入 F002，故为 4 张；新建库同口径为 5 张** | `ui-e2e-20261111-01-query.png` |
| 审核页 | 下拉列出 10 张 2026-09 发票，日期为 `2026-09-01` 形式，数据库当前值显示"（空）" | `ui-e2e-20261111-02-review-list.png` |
| 二次确认 | 弹窗复述企业/发票/字段/当前值/拟修改值/`operationId=8a441f8a-…`，并声明"未知结果不自动重试" | `ui-e2e-20261111-03-confirm-modal.png` |
| 保存与回读 | 面板显示 **`verified=true · 数据库回读已核验`**，`operationId: 8a441f8a-fa43-4083-85a7-50a4dc45e9d7`，`C001 / F009`，数据库用途=办公设备配件采购（合成测试） | `ui-e2e-20261111-04-verified.png` |
| 检查器 | 执行计划 6 步全部"已完成"（含"保存并回读核验 verified=true"），上下文来源四栏分离展示 | `ui-e2e-20261111-05-inspector-plan.png` |
| 补充表单 | `F004.purpose` + 明确值 + 用户原话 → "仅记录信息" → "明确事实已记录，发票未保存。请重新查询当前数据后审核。" | `ui-e2e-20261111-06-supplement-form.png` |

保存后数据库核对：`F009.purpose=办公设备配件采购（合成测试）`、`F002.purpose=办公用品采购（合成测试）`、`invoice_operations` 2 行且 `operation_id` 与弹窗一致、`invoices` 仍 40 行、`bank_entries` 仍 48 行、审计 0 行含金额或用途明文；此时 C001 2026-09 缺用途 **3 张**（F004/F003/F006）。

## 7. 未通过/未做的项（必须保留可见）

| 项 | 状态 | 说明 |
|---|---|---|
| 其他型号复测 | **不做（口径已定）** | 本项目统一使用 `qwen3.7-flash`；官方材料未指定型号，只要求可配置切换，故不引入 Qwen3.8-Max |
| "补充后再保存"同任务内闭环 | **受阻（设计约束）** | 补充清空待补字段后，下一次查询会让任务自动结项（`COMPLETED`），审核页随即禁用写入。演示时应在"等待补充"状态下直接审核保存（已验证可行），或把补充与保存拆成两段演示 |
| ≤5 分钟演示视频 | 未做 | 需录屏与讲解 |
| 最终作品 zip | 未做 | 需按白名单打包并跑 `secret_scan` 后人工复核 |
| 空缓存首次在线构建 | 未做 | 本机沿用既有 `.m2` 缓存 |
| 跨 JVM / 多实例并发 | 未验证 | 现有锁仅单 JVM 生效，不得宣称生产级 |

## 8. 复现命令

```zsh
cd "/Users/a123/Projects/财税 harness"
# 1) 前端
cd frontend && PATH=/Users/a123/.workbuddy/binaries/node/versions/22.22.2-3/bin:$PATH npm run build
# 2) 打包
cd .. && JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home \
  mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" -DskipTests -Dfrontend.skip=true clean package
# 3) 独立库
D=$(mktemp -d /tmp/taxagent-test-XXXXXX)
java -jar target/harness-0.0.1-SNAPSHOT.jar --initialize-data "--database=$D/test.db"
# 4) 启动（密钥从本机 .env 注入，勿写入日志）
set -a; . ./.env; set +a
java -jar target/harness-0.0.1-SNAPSHOT.jar "--taxagent.database=$D/test.db" --server.address=127.0.0.1 --server.port=18085
# 5) 回归
mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" -Dfrontend.skip=true test
python3 scripts/secret_scan.py
python3 scripts/osv_scan.py   # 需先 mvn dependency:list -DincludeScope=runtime -DoutputFile=target/dependency-list.txt
```
