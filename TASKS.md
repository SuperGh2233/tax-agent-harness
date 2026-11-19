# Tax Agent Harness — 共享开发任务池

> 项目：财税 Agent Harness（参赛队伍：用豆包就够了！）
>
> 状态：`TODO` 待认领 · `IN_PROGRESS` 开发中 · `BLOCKED` 阻塞 · `DONE` 验收通过。
>
> 这是开发执行台账；完整业务背景和设计以仓库中的《财税_Agent_Harness_项目计划书_V1.1_共享任务池.docx》以及官方 v1.1.0 材料为依据。如有冲突，以官方材料和双方确认后的决策为准。
>
> 历史记录中的 `/Users/...`、`/tmp/...` 等绝对路径仅为原开发 Mac 上的验证位置，不随 GitHub 仓库分发；新环境请按 REPRODUCE.md 使用自己的路径和独立临时数据库。

## 协作规则（不按人员固定分工）

1. **谁有空谁做**：从依赖已满足的 `TODO` 中选取优先级最高的一项。先修改该任务的“认领人/状态”，再开始编码；避免两人重复开发。
2. **一次一个主要任务**：两个开发者可并行处理互不冲突的任务，但不要同时修改同一文件或任务。发生冲突先同步再合并。
3. **小步提交**：每项任务应有独立、可复现的代码修改和最小测试；不要把无关变更一起提交。
4. **完成才标 DONE**：必须满足验收标准，记录测试命令、结果、Git commit（如有）和遗留问题。只写了代码、未验证则保留 `IN_PROGRESS`。
5. **阻塞可见**：改为 `BLOCKED` 时说明缺少什么、需要谁协助或等待什么；解除后恢复为 `TODO` 或 `IN_PROGRESS`。
6. **编码 Agent 也遵守本流程**：每次先读本文件、检查 Git 状态、只处理一个无冲突任务；完成后更新记录，禁止擅自扩大范围。
7. **不要私自改赛题规则**：金额计算、用户权限、企业隔离、保存字段白名单和规则版本以官方材料为准。任何不确定的字段或规则先列为待核查，不写死推测。

## 任务总表

| ID | 优先级 | 任务 | 依赖 | 状态 | 认领人 | 验收证据 |
|---|---|---|---|---|---|---|
| T00 | P0 | 仓库开发基线：README、任务台账与执行约定 | 无 | DONE | 初始化 | README.md、TASKS.md（仅文档） |
| T01 | P0 | 核对官方 v1.1.0 材料并冻结业务契约 | T00 | DONE | Coding Agent | docs/BUSINESS_CONTRACT.md；全部 5 表只读核对、40/48 复合键、Decimal 金额及 9 月必补 5/3 张复核通过；原件 SHA-256 未变；见 T01 完成记录 |
| T02 | P0 | 初始化 Java 后端工程、启动与测试骨架 | T00 | DONE | Coding Agent | JDK 21 下 mvn test：1/0/0；独立启动后 curl 健康检查 HTTP 200；见 T02 工作记录 |
| T03 | P0 | 导入官方发票、流水及规则示例数据 | T01、T02 | DONE | Coding Agent | JDK21 Maven 13 测试均通过；data/harness.db 已生成；40 张发票、48 条流水、5 条规则版本；见 T03 工作记录 |
| T04 | P0 | 定义工具 Schema、错误码、权限校验契约 | T01、T02 | DONE | ChatGPT / Coding Agent | JSON Schema、DTO、SQLite PermissionGuard；JDK21 Maven 70/0/0/0，见 T04 记录 |
| T05 | P0 | 实现只读发票及银行流水查询 | T03、T04 | DONE | Coding Agent | JDK21 全量 87/0/0/0；只读集成测试 17 项，见 T05 记录 |
| T06 | P0 | 实现规则管理、版本筛选和用途检查 | T03、T04 | DONE | Coding Agent | JDK21 全量 94/0/0/0；规则服务 7 项，见 T06 记录 |
| T07 | P0 | 实现模型驱动的 Agent Runtime 与 Tool Calling | T04 | DONE | Coding Agent | JDK21 离线全量 116/0/0/0；Fake 模型 + 本机 HTTP stub，真实模型未实测，见 T07 记录 |
| T08 | P0 | 实现任务记忆、状态持久化与跨轮恢复 | T02、T07 | DONE | Coding Agent | JDK21 全量 127/0/0/0；SQLite 记忆集成 11 项，见 T08 记录 |
| T09 | P0 | 实现允许字段的安全写入与回读验证 | T03、T04、T06 | DONE | Coding Agent | JDK21 全量 145/0/0/0，T09 18 项；可信授权、幂等台账、回滚及超时回查，见工作记录 |
| T10 | P0 | 端到端集成三类官方场景 | T05、T06、T07、T08、T09 | DONE | Coding Agent | JDK21全量158/0/0/0；三场景smoke 9/0/0/0；临时SQLite结构化证据，见T10记录 |
| T11 | P1 | 构建回归与故障注入用例 | T10 | DONE | Coding Agent | JDK21 离线定向91/0/0/0、全量163/0/0/0；两企业提交后任务保存故障及六项验收映射见T11验证记录 |
| T12 | P1 | 简易交互界面、审计日志与演示录制 | T10 | DONE | Coding Agent | JDK21 全量187/0/0/0（T12共24）；任务审计UUID隔离强断言、CSP及原T12验收通过，见安全修正记录 |
| T14 | P1 | 财税工作台 UI/UX 视觉重构 | T12 | DONE | Coding Agent | [T14计划](docs/plans/active/PLAN-20261030-t14-ui-ux.md)：JDK21全量194/0/0/0、DemoApiTest24、八断点/52本地资源通过 |
| T13 | P1 | 打包提交说明及复现文档 | T11、T12 | IN_PROGRESS | Coding Agent | JDK21全量223/0/0/0、前端Vitest71通过；真实Qwen只读API跨轮通过；浏览器最终回复MODEL_TIMEOUT，空缓存/全新checkout、真实写入UI E2E未证实；见 [交接说明](docs/agent-handoff.md) 与T13记录 |

> **注意**：T00 的 DONE 仅代表本次建立共享文档，不代表代码已开始开发。其他任务均需实际验收后才可标记完成。

## 第一阶段执行顺序

- **先做 T01 + T02**（不同文件可并行）：冻结业务契约与建立可运行工程骨架。
- 然后做 **T03 + T04**：官方数据可导入、工具接口先统一。
- **T05 / T06 / T07** 可在依赖完成后逐步并行，尽早联调一条真实查询链路。
- 优先打通 **T10**，再考虑界面美化、向量检索或复杂编排。

## 完成记录模板（复制到下方）

```md
### Txx — 任务名称
- 状态：DONE
- 认领人：
- 完成日期：
- Commit：
- 变更文件：
- 验收命令：
- 验收结果：
- 遗留问题：
```

## 工作记录

### P0-3 — Anthropic 多工具调用与回放兼容性修复
- 状态：DONE（离线回归验收）；2026-11-02。真实浏览器单工具 query_invoices 成功，但发票+银行双工具请求出现 MODEL_RESPONSE_INVALID。原适配器只允许一个 tool_use，且 LlmClient/AgentRuntime 不支持单轮批量 Call。
- 变更：旧 Codex 会话因交互超时中断；按用户要求改由 GPT-Free-Mac 文件写入工具完成。仅修改 LlmClient、AnthropicMessagesClient、AgentRuntime 以及 TASKS.md，新增 MultiToolCallTest（5 项）。支持最多 8 个批量工具、Anthropic assistant 消息单次回放与多个匹配 tool_result、串行执行只读工具；整批预校验，禁止混合 update_invoice，保留 P0-1 写入证据门和 P0-2 自助核验及原有未提交修改。
- 验收：JDK21 离线 Maven 定向（MultiToolCallTest、AnthropicMessagesClientTest、AgentRuntimeTest、FinanceAgentIntegrationTest）54/0/0/0；全量 Java 219/0/0/0；git diff --check 通过。因 ServerFS 编辑保留源文件时间戳，首次测试读到旧 target/classes；只 touch 三个 runtime 文件刷新 mtime 后，Maven 正确编译 LlmClient$Batch.class，再次验证通过。Codex 仅运行测试/mtime 更新，没有编辑源码内容。没有 clean、没有修改审计docs、没有暂存/提交/推送或外网请求。
- T13 打包跟进（2026-11-02）：P0-3 完成后重新执行 JDK21 离线 `mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" -DskipTests package`，BUILD SUCCESS；JAR `target/harness-0.0.1-SNAPSHOT.jar`（34,129,319 bytes）确认包含 `LlmClient$Batch.class`、`AnthropicMessagesClient.class`、`AgentRuntime.class`；`git diff --check` 通过。17 个已修改、22 个未跟踪、0 暂存；HEAD 仍为 `6af75cebbf60205375db81af6a5ff25ec212d829`。未启动服务、未读取凭证、未访问网络/数据库、未修改源码、未 clean/提交/推送。
- T13 真实 Qwen 只读联调（2026-11-02，用户已轮换凭证、启动独立实例）：本机 127.0.0.1:18084 健康检查 HTTP 200/UP；在 `/tmp/taxagent-qwen-e2e-20261102-08e66a4/demo.db` 官方合成数据上，通过 Demo API 真实调用 `query_invoices` → `query_bank_entries` → `check_invoice_purpose`，三工具均成功，发票 962206 分、银行收入 1300050 分、支出 718074 分、缺用途 5 项与独立库一致。模型返回 FINISHED、TaskMemory 直接 COMPLETED（版本 1），未按任务目标追问 F002 用途；故未补充/第二轮，跨轮能力本次未通过。调用链验证不等同确认供应商在同一个模型响应中批量发出 tool_use。全量 invoices/bank_entries 与 F002 用途/备注前后相同，invoice_operations=0，未授权写入=0，未调用 update_invoice；实例未被停机，Git 未提交/推送。任务 ID 5f3e033e-50ea-47db-be40-0b4eeda34686。
- T13 必填 pending 完成门控修复（2026-11-02）：由 GPT-Free-Mac 直接修改 `TaskMemory.java`。服务端不再直接信任模型 `Finish`：只要结构化必填 pending 仍存在，返回结果改为 `NEEDS_INPUT`、任务保持 `WAITING_USER`，生成明确的字段补充提示；用户通过结构化 supplement 清空 pending 后，才允许后续 Finish 变为 COMPLETED。若本轮有精确匹配的 `ApprovedWrite`，只有当前轮 `update_invoice` 参数与审批一致、工具返回 `data.verified=true` 且回读键和值完全匹配时，才清除对应的 `<invoiceId>.<field>` pending；其他字段继续等待，模型文本/参数/历史均不能清除待办。普通无 pending 查询仍可正常完成。
- 新增/更新回归：`TaskMemoryTest` 覆盖提前 Finish 阻断、补充后恢复、无 pending 正常完成、已核验写入只解除精确字段；`DemoApiTest` 覆盖 HTTP 返回与任务状态。JDK21 离线定向 70/0/0/0；最终完整套件 223/0/0/0（BUILD SUCCESS）；`git diff --check` 通过。未改审计docs、前端、冻结契约或种子。
- 新 JAR：JDK21 离线 `mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" -DskipTests package` BUILD SUCCESS；`target/harness-0.0.1-SNAPSHOT.jar` 34,131,343 bytes，确认包含 TaskMemory、AgentRuntime、AnthropicMessagesClient。Codex 仅运行测试/打包和 mtime 刷新，源文件写入由 GPT-Free-Mac 完成；没有 clean、暂存、提交或推送。
- T13 真实 Qwen 完成门控与跨轮恢复 E2E（2026-11-02，用户重启新 JAR 后）：`127.0.0.1:18084/api/health` HTTP 200/UP。通过真实 Demo API，使用 `viewer/C001` 只读身份，在同一独立合成库创建任务，pending=`F002.purpose`。第一轮 HTTP 200，真实 Qwen 依次调用 `query_invoices`、`query_bank_entries`、`check_invoice_purpose`；result=`NEEDS_INPUT`、task=`WAITING_USER`、version=1，question 包含 F002.purpose。用一条明确标注为合成测试信息的结构化 supplement 清空 pending 后，第二轮真实 Qwen 再次完成三项只读工具调用；result=`FINISHED`、task=`COMPLETED`、version=3。两轮均未尝试 `update_invoice`，无 errorCode，无模型重试。前后 SQLite 只读快照一致：C001 发票20行、银行流水24行，两表 SHA256 不变，F002 purpose/remarks 仍为空，invoice_operations=0。只写入预期的任务/审计元数据；未触碰 data/harness.db、真实客户数据或仓库文件，未停止服务。结论：模型提前 Finish 被服务端正确拦截；真实 Qwen 的追问→用户补充→恢复完成链路通过。
- T13 真实浏览器 UI 只读 E2E（2026-11-02）：通过独立临时配置的 Chrome 156 + CDP 访问 `127.0.0.1:18084`；页面、JS、CSS、字体 HTTP 200，favicon.ico 404（非核心资源）。UI 成功选择 `viewer/C001`、创建唯一任务并发送一次只读 turn；三个只读工具均返回成功，但随后模型最终回复触发 `MODEL_TIMEOUT`。只读 API 诊断确认任务仍为 `ACTIVE`/version=1、pending=`F002.purpose`、无 question；审计顺序为 start COMPLETED → turn REQUESTED → query_invoices/query_bank_entries/check_invoice_purpose TOOL_RESULT → turn ERROR/MODEL_TIMEOUT。未补充、未发送第二轮，未调用 update_invoice。前后合成 DB 快照一致：发票/流水行数 20/24、SHA256 不变、F002 用途/备注空值状态不变、invoice_operations=0。临时 Chrome 和 profile 已清理；服务与仓库未改动。结论：浏览器加载、会话选择、建任务和发送消息通过；完整 WAITING_USER→supplement→COMPLETED UI 链路因 Qwen 第二阶段超时未完成，不能记为 UI E2E 通过。当前 `viewer/C001` 存在一条 ACTIVE 未完成任务（ID 后缀 `46c64d16`），后续必须恢复该任务，不能再创建第二条。
- Qwen 延迟适配（2026-11-02）：根据真实 UI E2E 在三项只读工具成功后发生 `MODEL_TIMEOUT` 的证据，`AnthropicMessagesClient` 调整为首个模型请求（无工具历史）使用 `output_config.effort=medium`，有工具结果历史的后续请求使用 `low`，保留 8192 max_tokens 和现有 60 秒上限。官方 Anthropic-compatible Messages 文档确认 qwen3.8-flash 支持 low/medium/xhigh；low 用于缩短工具结果后的总结阶段，不改变工具白名单、批量预校验、权限或写入授权。`AnthropicMessagesClientTest` 断言首轮 medium、工具结果后 low。
- 适配验收：JDK21 定向测试 88/0/0/0；完整 Maven 套件 223/0/0/0；离线 package BUILD SUCCESS；新 JAR `target/harness-0.0.1-SNAPSHOT.jar` 34,131,390 bytes。只刷新两处 Java 源/测试文件 mtime 后重编译；未运行新的云端模型请求，未重启当前服务。Git HEAD 仍为 `6af75cebbf60205375db81af6a5ff25ec212d829`，未提交/推送。
- 剩余：请用户用新 JAR 重启 18084 服务后，先恢复现有 ACTIVE 任务（不新建），验证中/低 effort 后的 Qwen 追问与 UI 补充恢复；再验证真实浏览器 UI 完整链路。仍需确认供应商同一响应多个 `tool_use` 的真实兼容性、受控写入及自助核验的 UI E2E。受控写入必须由演示 UI 当前轮明确授权、使用合成样本，只执行一次且不自动重试；此前 viewer 测试均未执行财务写入。JDK21 为已测构建环境；后续复现仍建议 JDK21。

### P0-2 — 不确定发票写入的自助只读核验
- 状态：DONE；认领人：Coding Agent；2026-11-02。先保存 IN_PROGRESS、添加后端失败测试，再实现原子台账 task_id、本轮授权绑定及任务范围恢复、CSRF 只读核验、元数据审计和双前端手动入口。仅精确 VERIFIED、布尔 verified=true 且成功删除存储才清锁；其他结果保留锁且不重试写入。
- 行为：`POST /api/demo/tasks/{taskId}/operations/{operationId}/verify` 使用现有 mutationLock，READ 权限及同一只读事务先验证任务归属，再按 task/user/enterprise/operation 精确查询台账及当前用途/备注。只有 VERIFIED 返回 UpdateResult；NOT_FOUND、DIVERGED 不返回财务内容。查询连接启用 query_only，不初始化 Schema、不调用 execute；每次有效核验追加 taskId/operationId/status 元数据，审计失败 HTTP 500。React 与实际静态 demo.js 均支持从 sessionStorage 恢复后手动核验；未知编号不请求，失败/异常/不匹配/非布尔核验/存储删除失败保留锁，普通查询仍可用。
- 迁移：仅 invoice_operations 新增可空 task_id；构造写服务时检测旧表后 ALTER ADD COLUMN，重复初始化安全，不回填或猜测旧关联、不改业务表/种子。所有新写入的 task_id 与发票更新、台账插入同事务；Authorization、ApprovedWrite 和 TaskMemory 校验任务绑定，恢复调用同任务。旧无关联记录核验 NOT_FOUND；跨任务复用 operationId 写入拒绝，既有同任务幂等保留。
- 精确变更文件：TASKS.md；src/main/java/com/taxagent/harness/demo/DemoController.java；src/main/java/com/taxagent/harness/runtime/AgentRuntime.java、FinanceAgent.java、TaskMemory.java；src/main/java/com/taxagent/harness/tool/InvoiceWriteService.java；src/main/resources/invoice-operations.sql、static/index.html、static/demo.js；src/test/java/com/taxagent/harness/DemoApiTest.java、InvoiceWriteServiceTest.java、FinanceAgentIntegrationTest.java；frontend/src/api.ts、App.tsx；frontend/tests/api.test.ts。无新依赖。
- 后端证据：先行 `-Dtest=DemoApiTest#lostResponseCanBeVerifiedRepeatedlyWithoutFinancialWrites+verificationFailsClosedForMissingMismatchedLegacyAndDriftedOperations+verificationRequiresCsrfCanonicalIdsAndSuccessfulAudit`：3 项/3 失败/0 错误（端点缺失，15:50:45）。最终 `JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" -Dtest=DemoApiTest,InvoiceWriteServiceTest,FinanceAgentIntegrationTest test`：77/0/0/0（15:57:15）；同命令去掉 -Dtest 全量：214/0/0/0（15:57:39）。覆盖丢响应、重复只读/数据库字节一致、任务/用户/企业隔离、READ 权限复检、漂移/发票缺失、旧库迁移、CSRF/非法 ID、锁串行及已提交结果的审计失败。
- 前端证据：frontend 下 `npm run verify`（依次 typecheck、lint、npm test、build）全部通过，Vitest 71 项（15:56:05）；实际 demo.js VM 与 React 使用的 helper 覆盖刷新恢复及全部失败保锁分支，无自动重执行；`node --check src/main/resources/static/demo.js`、`git diff --check` 通过。沙箱 shell 临时文件、Vitest 临时目录受限后获准在沙箱外离线运行；现有 Mockito 动态 agent 警告不影响通过。
- 保留核对：P0-1 verifiedWrite 生产门控原文未改，全部原测试保留，仅必要的 taskId 签名适配；P0-1 工作记录原文完整。审计docs 目录内21个文件 SHA-256 与开工基线一致，仍未跟踪；冻结契约、README、计划/发布材料、业务字段/规则/权限/种子均未编辑。main / HEAD 6af75cebbf60205375db81af6a5ff25ec212d829 不变，暂存区为空，无提交/推送、外部服务/网络、凭证或现有业务库访问，无构建清理。
- 剩余边界：无本任务阻塞；现有锁仅保证单 JVM 内串行，核验反映查询时的快照，之后外部修改不由本入口阻止。旧无关联、NOT_FOUND、DIVERGED 或损坏存储仍需人工核查，绝不据此重试。React 验证为 helper/类型/构建覆盖，静态页为实际脚本 VM 覆盖，未做真实浏览器手工验收。

### P0-1 — 本轮批准写入的任务完成证据门控
- 状态：DONE；认领人：Coding Agent；完成日期：2026-11-02。仅后端 runtime 与测试；先添加回归并验证失败，再以本轮服务端写入结果限制 Finish，普通查询保持可完成。不得以模型文本、参数或旧轮历史证明保存；保留精确授权、权限、幂等、审计及恢复行为。不改审计docs、前端、数据库契约或其他计划，不提交推送。验收：JDK21 离线定向/全量 Maven、diff --check 及精确状态核对。
- 验收完成：DONE。仅 AgentRuntime.java、FinanceAgentIntegrationTest.java 及本记录；证据为本轮 execute 的强类型 UpdateResult（verified、发票键及批准值匹配），operationId 沿用 execute 的可信授权绑定，不存储跨轮标志；缺证据的 Finish 返回 WRITE_NOT_VERIFIED/ACTIVE 和安全错误文本，Ask 及普通查询行为保留。
- 命令：`JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" -Dtest=FinanceAgentIntegrationTest test`；修复前22项/4失败/0错误；最终23项全通过。相同命令移除 `-Dtest=FinanceAgentIntegrationTest` 后全量206项/0失败/0错误/0跳过（15:38:35 +08:00）。新增8项覆盖无调用、模型伪造核验文本、参数伪造、旧轮证据、普通 Finish、清空及未核验/不匹配返回；沿用真实保存、精确参数、失败回滚及恢复用例。最初沙箱因JUnit临时目录权限失败，获准离线重跑；中间修正安全错误文本断言及 mock 未初始化台账的测试夹具，无生产额外改动。
- 收尾：git diff --check 通过；审计docs 全部22文件SHA-256与开始一致，仍未跟踪；无外网、凭证或现有业务库访问，无清理、暂存、提交或推送。无本任务未解决项；既有超时未知结果继续走只查询恢复，不自动重试。

### T07 — 模型驱动 Agent Runtime 与 Tool Calling
- 状态：DONE；认领人：Coding Agent；完成日期：2026-10-30。先读 TASKS/README/业务及工具契约、Schema、PermissionGuard、只读服务和 Git 状态，确认 T04 DONE/T07 TODO；先保存 IN_PROGRESS 和计划后编码。
- 变更：新增 runtime/LlmClient.java、AgentRuntime.java、OpenAiCompatibleClient.java；AgentRuntimeTest.java、runtime/OpenAiCompatibleClientTest.java；docs/plans/active/PLAN-20261030-agent-runtime.md；更新 README.md、docs/index.md、TASKS.md。无新依赖，未修改现有服务、DTO、权限守卫、Schema、种子、数据库或冻结契约。
- 行为：可替换模型返回 Call/Ask/Finish，生产无关键词路由；仅三个只读工具，Schema 直接取冻结 $defs，严格 DTO 参数解码（包括未知字段/类型/重复字段/尾随 JSON 拒绝），每次执行由现有服务事务复检可信 Session 权限。Session 不进入模型请求，模型不能改变用户身份。执行结果及错误带调用 ID 回传；任何工具失败使本次请求最终 ERROR，不展示模型伪造成功文本。仅当前请求历史，无跨轮/跨会话记忆。
- 限制：默认 8 步、每操作 15 秒、无重试；可配置至多 32 步/60秒/1次模型重试、三个工具子集；工具无重试。明确无密钥、模型故障/超时、工具超时及步数耗尽错误。adapter 使用 JDK HttpClient、完整 endpoint/key/model 显式注入；无配置不联网，不跟随重定向，单步单工具、非流式；未知/写入/管理/记忆工具一律拒绝。第三方替换客户端需响应中断，取消不保证强杀线程。
- 验收命令：`JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test`（获一次性许可在沙箱外执行，离线依赖缓存；测试仅本机端口）。2026-10-30 01:24:23 +08:00 BUILD SUCCESS，实际 Java 21.0.12.1；116 tests / 0 failures / 0 errors / 0 skipped，原有 94 + T07 22，报告 target/surefire-reports（忽略）。
- 覆盖：FakeLlmClient 多次决策、流水/发票/用途真实只读执行及完整结果回传、追问/结束、请求隔离、权限拒绝后模型伪报成功、未知及写工具拒绝、伪造身份/恶意JSON、白名单和 Schema、步数、异常、有限重试与超时、缺可信会话、存储故障不冒充空结果；临时库查询前后字节一致。本机 HTTP stub 验证 Schema/历史/调用 ID 协议、无密钥零请求、追问/结束/工具解析及截断/畸形拒绝。
- 失败与修复：首次 JDK21 编译发现条件 Class 泛型推断错误，改为两个明确 decode 分支后全量通过。沙箱内 JLine 系统临时目录警告，实际全量测试按既有环境约束在沙箱外执行；现有 Mockito agent 警告保留，不影响通过。
- 验收边界：**未联网调用真实模型，未验证真实供应商兼容性**；本次证明离线模型接口、决策执行循环和协议 stub 可用，不声称真实模型端到端完成。未部署、未执行数据初始化到运行库、未写官方数据。T08/T09/T10 保持 TODO；不实现安全写入、长期记忆、多 Agent 或前端。
- 审查：官方三份材料与计划书 SHA-256 与冻结基线一致；Git 原有全部未跟踪状态保留，构建/依赖缓存未入库；未提交、未推送。无需跨会话交接，当前任务完成。

### T00 — 仓库开发基线
- 状态：DONE（仅文档）
- 完成日期：2026-10-29
- 变更文件：README.md、TASKS.md
- 验收：文档已建立；代码和测试尚未启动
- Commit：未提交
- 遗留问题：待核对官方文件内部字段并初始化工程（T01/T02）。

### T01 — 核对官方 v1.1.0 材料并冻结业务契约
- 状态：DONE（仅本地材料核对与文档冻结，不代表业务实现完成）
- 认领人：Coding Agent
- 开始/完成日期：2026-10-29；工作分支：main（沿用原分支）
- 认领过程：已先将 T01 从 TODO 改为 IN_PROGRESS 并保存，再建立契约；完整读取、审查、保存及复算后改为 DONE。
- Commit：未提交；未推送（按本次授权）。
- 变更文件：TASKS.md、docs/BUSINESS_CONTRACT.md；其余文件保留，T02 保持 TODO。
- 验收命令：`git status --short`、`git branch --show-current`；本机 `python3 -B -c` 使用 zipfile/ElementTree 读取三份官方材料和计划书，使用 decimal.Decimal/Counter 复算，使用 hashlib.sha256 前后核对原件。可复制的只读复验代码见 BUSINESS_CONTRACT.md“审查与验收”。
- 验收结果：完整读取官方 Word 正文/表格及页脚、Excel 全部 5 表；发票 40、银行流水 48，复合键分别唯一；每企业每月发票 10、流水 12（8 支出/4 收入）；7 月无记录；9 月必补 C001=F002/F004/F003/F006/F009，C002=F002/F005/F006；6 组金额及 5 条规则版本、权限和白名单均已形成带出处的契约。复算输出 PASS；契约首次保存后回读确认 18313 字节，随后补充本复验代码并再次检查保存。三份官方原件及计划书哈希前后一致，详见契约哈希表。
- 审查：逐项对照原文和现有计划；澄清 R004 精确名称、内部截止与技术选型、内部增强项、双台账编号；无阻塞现有初赛契约冻结的重大不确定。未执行应用测试，因为未创建应用代码。
- 遗留问题：主办方截止/入口/大小限制、失效日期、未来版本冲突策略、规则管理权限及用途/备注未规定边界均标待核实，禁止推定。核对区 E14/F16 有浮点尾差，按明细十进制核算一致。macOS 命令启动输出临时缓存权限警告，但 Python/Git 均退出 0，实际解析和只读复算完成。

### T02 — 初始化 Java 后端工程、启动与测试骨架
- 状态：DONE；认领人：Coding Agent；完成日期：2026-10-29。
- 认领过程：先读 TASKS.md、README.md、冻结契约及 Git 状态，确认 T01 DONE / T02 TODO，先保存 T02 为 Coding Agent / IN_PROGRESS 后编码；仅完成 T02，T03 及其他任务状态未改。
- Commit：未提交、未推送；原有未提交文档和材料保留。
- 变更文件：pom.xml；src/main/java/com/taxagent/harness/{HarnessApplication,HealthController}.java；src/main/resources/application.yml；src/test/java/com/taxagent/harness/HealthIntegrationTest.java；.gitignore；.env.example；README.md；AGENTS.md；docs/index.md；docs/plans/active/PLAN-20261029-backend-bootstrap.md；TASKS.md。
- 技术：Java release 21、Spring Boot 3.5.16、Maven、SQLite JDBC 3.53.4.0 runtime、JUnit 5；SQLite 未连接、未建表、未导入，无业务或模拟功能。接口仅表示应用存活。
- 环境：默认 java 25.0.4.1；另有已安装 JDK 21.0.12.1；Maven 3.10.0。验收命令均以 `JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home` 前缀执行，不修改系统或全局配置。
- 验收命令：`mvn -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test`。2026-10-29 21:39:16 BUILD SUCCESS，Tests run: 1, Failures: 0, Errors: 0, Skipped: 0。完整应用在随机端口 53630 启动，经真实 HTTP 断言状态码、JSON 类型与精确响应。报告：target/surefire-reports/com.taxagent.harness.HealthIntegrationTest.txt（构建产物已忽略）。
- 启动证据：同一 JAVA_HOME 前缀执行 `mvn -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" spring-boot:run`，21:40:06 日志显示 Tomcat 8080、Started HarnessApplication；`curl --fail --show-error --silent -i --max-time 5 http://127.0.0.1:8080/api/health` 退出 0，HTTP 200 / Content-Type application/json / `{"status":"UP"}`；验证后 Ctrl+C 停止。
- 失败与处理：最初 here-document 因系统临时目录只读未生成文件，紧接的 Maven 因缺 pom.xml 失败；改用直接文件补丁。一次 MAVEN_OPTS 临时路径因仓库路径含空格被分词，JVM 启动失败，移除此参数。首次实际测试编译成功但 Tomcat/Surefire 无法创建系统临时目录，1 test / 1 error；获准沙箱外重跑同一命令后通过。依赖下载成功，无最终网络阻塞，缓存仅在 .m2/，未修改全局仓库配置。
- 检查：Git 状态复查；.m2、target、.env 已忽略，.env.example 可入库且无密钥。官方三份材料与计划书 SHA-256 与 T01 表一致；冻结契约未写入或移动。
- 遗留风险/后续：测试有 Mockito/Byte Buddy 动态 agent 警告，但 Java 21 测试成功；默认 Java 25 未作验收，按 README 选择 Java 21。受限沙箱须允许系统临时目录与本地端口。新环境首次运行需下载依赖。健康接口不证明数据库或业务就绪；后续 T03 需另行授权实施。

### T03 — 官方示例数据导入与初始化
- 状态：DONE；认领人：Coding Agent；核对日期：2026-10-29。
- 文件：scripts/official_seed.py、src/main/java/com/taxagent/harness/{DataInitializer,HarnessApplication}.java、src/main/resources/seed/{schema.sql,official-v1.1.0.json}、src/test/java/com/taxagent/harness/DataInitializerTest.java。
- 证据：使用 JDK 21 Maven 测试在沙箱外重跑成功，共 13 项测试，失败 0、错误 0、跳过 0；测试覆盖原始种子核对、行数、六组月份统计、9 月用途 5/3、权限与版本、重复初始化不覆盖用户值、失败回滚。
- 初始化产物：data/harness.db 已存在，61440 字节（Git 忽略）；数据从官方文件提取，初次写入与运行时数据分离，初始化使用 INSERT ON CONFLICT DO NOTHING。
- 异常说明：受限沙箱不允许 SQLite 使用系统临时目录；获一次性许可后测试通过。原 T03 Agent 在收尾记录前长时间无更新，已取消以避免遗留写入竞争；由协作方核对测试事件与数据库文件后补记。本条未再次独立重跑全部测试。
- Commit：未提交；T04 及以后未开始。

## 待确认事项

- T02 按本次用户授权采用 Java 21 + Spring Boot + Maven + SQLite JDBC + JUnit 5；SQLite 仅预备依赖，数据导入属于 T03。
- T01 已完成本地官方材料核对，后续以 docs/BUSINESS_CONTRACT.md 为依据；其中待核实项不得作为已确认规则。
- Git 当前 `main` 已跟踪 `origin/main`；2026-11-02 交接提交前基线为 `6af75cebbf60205375db81af6a5ff25ec212d829`。本轮仅提交明确列出的 24 个修改文件和 2 个新增文件；本地 `审计docs/` 目录不纳入远端。

### T04 — 工具 Schema、错误码与权限契约
- 状态：DONE；认领人：ChatGPT / Coding Agent；完成日期：2026-10-30。沿用已有 IN_PROGRESS，未重新认领或重置。
- 范围：新增 docs/plans/active/PLAN-20261030-tool-contracts.md、docs/specs/TOOL_CONTRACT.md、src/main/resources/tool-contract.schema.json、tool 包下 ToolContract/ErrorCode/DomainException/PermissionGuard，以及 ToolContractTest、PermissionGuardTest、ToolSchemaTest；更新 README、docs/index.md 和本台账。REQ/PLAN 于 T04 验收收尾时补齐，未追溯声称其先于实现。
- 实现：不可变输入输出、严格 JSON 解码、可检查的 draft 2020-12 输入 Schema；必填/可选/条件字段、类型、枚举、additionalProperties:false 均显式定义。Schema 与 DTO 字段及代表性输入一致性测试通过。Session 不可由工具入口解码，工具参数拒绝 user_id；权限每次从 T03 permissions JOIN users/enterprises 读取，未知主体或缺行默认拒绝，U001 两企业读写，U002 C001 只读/C002 拒绝。仅 purpose/remarks 可作为更新字段；规则管理权限未确证，默认拒绝。
- 验收命令：JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test。最终 2026-10-30 01:13:57 +08:00 BUILD SUCCESS，Java 21.0.12.1，70 tests / 0 failures / 0 errors / 0 skipped；Health 1、T03 12、PermissionGuard 16、ToolContract 21、ToolSchema 20。报告位于 target/surefire-reports（忽略）。
- 失败与修复：初次沙箱测试 50 项，1 failure（Jackson 数字转字符串）和 29 errors（系统临时目录被拒）；显式禁止文本强转后，一次性沙箱外完整测试 50 项通过；用户补充要求 Schema 后新增 20 项，再经一次性沙箱外完整测试 70 项通过，未保存命令授权前缀。保留原有 Mockito 动态 agent 警告，未影响验收。
- 审查：无新增依赖，无 SQL 拼接输入，无业务读写执行器；集合使用不可变快照。未实现 T05/T06/T07/T08/T09，未改其他任务状态；官方原件/计划书、冻结业务契约、现有 schema/种子及他人改动未写入或清理。原 T03 原件逐字段核对测试通过。
- 遗留边界：T07 负责可信会话接入、按规范 Schema 适配供应商；T09 负责精确用户授权、同事务权限复检、幂等写入和真实回读。规则管理角色待核实，目前拒绝。
- Commit：未提交、未推送。

### T05 — 只读发票和银行流水查询与金额汇总
- 状态：DONE；认领人：Coding Agent；完成日期：2026-10-30。先读取任务、README、业务/工具契约、现有 DTO/守卫/schema/测试及 Git 状态，确认 T03/T04 DONE、T05 TODO，保存 IN_PROGRESS 后编码。
- 变更文件：新增 tool/ReadOnlyFinanceTools.java、ReadOnlyFinanceToolsTest.java、docs/plans/active/PLAN-20261030-read-only-finance.md；更新 README.md、TASKS.md、docs/index.md。
- 实现：默认 data/harness.db，通过 SQLite JDBC mode=ro 读取，缺库不创建；可信 Session 独立传入，每次同一事务复检企业读取权限；PreparedStatement 绑定企业及月份起止日期。发票记录/合计、流水记录/收入/支出独立返回；整数分精确累加、LocalDate、保留企业复合键。无认证链路，未暴露 HTTP 查询入口。无权限、无会话、数据库失败按既有错误码区分。
- 验收命令：`JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test`。2026-10-30 01:17:09 +08:00 BUILD SUCCESS；实际 Java 21.0.12.1；87 tests / 0 failures / 0 errors / 0 skipped。T05 17、既有 70 全通过，报告 target/surefire-reports（忽略）。
- 覆盖：T03 初始化的临时 SQLite 六组金额（含两企业 7 月全零、8/9 月）、10 发票/12 流水（8 支出/4 收入）、企业/月过滤、用户权限矩阵、U002 C002 拒绝、未知主体/企业、注入形态企业输入、每次权限撤销复检、缺会话/查询、伪造 user_id 解码拒绝、跨企业同号复合键、实时数据/用途/备注、月首/月末、查询前后数据库字节不变、缺表/缺库/权限读取故障及 cause 保留。
- 环境失败：首次沙箱内编译通过，全量 87 项因系统临时目录/本地 HTTP 限制出现 46 errors；获一次性沙箱外执行许可后同命令全部通过。既有 Mockito 动态 agent 警告不影响结果。
- 范围审查：只完成 T05，T06/T07/T09 保持 TODO；未新增依赖、匹配或修改功能；未改现有 DTO/PermissionGuard/schema/种子、官方原件/计划书/冻结契约和他人文件内容；原件与种子逐字段核对测试通过。Git 原有文件全部未跟踪，保持原状；未提交、未推送。
- 遗留：可信会话认证接入留后续任务；当前仅服务层。无阻塞。

### T06 — 规则服务与用途检查
- 状态：DONE；认领人：Coding Agent；2026-10-30。确认 T03/T04/T05 DONE，先保存 IN_PROGRESS 后编码，实际全量测试成功后标 DONE。
- 范围：SQLite 本地服务，权限内规则检索、显式受信管理上下文导入新版本及停用；事务保证版本/企业关联一致，保留旧版本并拒绝覆盖及适用版本歧义。用途仅 2026-09，已知官方版本及正文确定性执行，未知内容拒绝；不做通用 RAG、T07/T09 或 HTTP 接口。
- 验收：甲5乙3、1000元边界、8月拒绝、停用过滤、当前数据变化、U002权限及管理拒写、版本/关联/事务与异常测试；JDK21 全量 Maven 通过后才标 DONE。

- 变更文件：新增 tool/RuleService.java、RuleServiceTest.java；更新 README.md、TASKS.md、docs/specs/TOOL_CONTRACT.md；无新依赖/迁移/计划文档。
- 验收命令：`JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test`。实际 Java 21.0.12.1，2026-10-30 01:20:28 +08:00 BUILD SUCCESS，94 tests / 0 failures / 0 errors / 0 skipped；T06 7、既有 87 全通过；报告 target/surefire-reports（忽略）。
- 覆盖：甲5乙3、规则名称/ID/版本/完整正文、日期/状态/企业筛选、1000元等于阈值和999.99低额、备注不替代用途、当前DB用途更新与企业隔离、8月拒绝、停用不回退、未知正文/版本拒绝、启用多版本歧义、U002读C001/拒C002、所有普通会话管理拒写、显式受信管理、导入/重复冲突/未知企业/非法状态/版本记录/全企业停用、关联插入故障整事务回滚、只读不改库、缺库/缺表错误。
- 环境失败：首次沙箱内编译成功，94项中53 errors，原因是系统临时目录及本地HTTP限制；获一次性沙箱外执行许可后同命令全部通过。已有 Mockito 动态 agent 警告不影响结果。
- 边界：管理角色仍未由官方定义，本地管理 capability 只允许受信代码显式创建，未接入 LLM 或 HTTP；不支持任意文本语义执行，未知规则可保存检索但不可冒充已支持的用途算法。版本优先级未定义，相关启用版本重叠时报冲突。
- 范围审查：T07/T09 未实施；官方原件、计划书、冻结契约、schema/种子与已有代码均未修改，T03 原件/种子逐字段核对测试通过；未清理已有文件，未提交、未推送。无阻塞。

### T07 — 模型驱动 Agent Runtime（当前请求内）
- 状态：DONE；认领人：Coding Agent；完成日期：2026-10-30。
- 范围：新增 runtime/LlmClient、AgentRuntime、OpenAiCompatibleClient 及 FakeLlmClient、本机 HTTP stub 测试，更新 README.md。未实现登录与业务 HTTP API、T08 跨轮记忆、T09 写入、真实模型调用或生产配置。
- 验收：JDK 21.0.12.1，2026-10-30 01:24:23 +08:00，BUILD SUCCESS，116 tests / 0 failures / 0 errors / 0 skipped（T06 的 94 项上新增 22 项）。AgentRuntimeTest 为 20 项；全量 Surefire 报告在 target/surefire-reports（忽略）。
- 覆盖：多工具调用及上下文回传、追问/结束、请求隔离、权限及恶意参数拒绝、写工具禁用、步数上限、超时和模型错误、缺配置时零网络。
- 边界：仅 FakeLlmClient 与本机 HTTP stub 离线验收；未联网调用真实模型，真实供应商协议兼容性未实测。超时取消依赖客户端响应中断。
- Git：未提交、未推送；已有未跟踪项目文件保留。T08 及后续任务不在本阶段范围。


### T08 — 任务记忆、状态持久化与跨轮恢复
- 状态：DONE；认领人：Coding Agent；完成日期：2026-10-30。先检查 Git、TASKS、README、冻结业务契约、Runtime/LlmClient/ToolContract、PermissionGuard、SQLite schema 和现有测试报告，确认 T07 DONE/T08 TODO，先保存 IN_PROGRESS 后编码。仅推进 T08，T09/T10 未认领。
- 变更：新增 runtime/TaskMemory.java（嵌套不可变 DTO）、resources/task-memory.sql、TaskMemoryTest.java；AgentRuntime.java 增加 scoped 上下文调用与工具企业约束；README.md、TASKS.md、docs/index.md、T08 REQ/PLAN 和存储 SPEC。
- 行为：SQLite 独立任务/偏好表，原业务数据不写；状态 ACTIVE/WAITING_USER/COMPLETED/CANCELLED，待补字段、用户明确补充及原话、最近追问、工具名/成功或错误码摘要持久化。每次服务访问同事务复检当前 READ 权限，以 Session 用户+企业绑定，拒绝无会话及无权访问。部分唯一索引限制每用户企业一个未完成任务，乐观版本防止旧轮覆盖取消/更正。跨对话和 service 重建恢复 WAITING_USER；任务查询/继续/取消、补充更正、偏好查看/新增/更正/删除已实现。
- 模型边界：Runtime 保持 LLM 决策，无关键词路由；上下文仅为历史用户声明，不自动写模型猜测为事实/偏好、不把旧工具结果代替当前查询。仅可信后端可调用明确输入方法并负责核对用户原话；未实现自然语言授权识别。模型不能伪造 user_id，当前任务工具不能切到其他企业。无新增 HTTP/认证签发入口，无 T09 业务写入或 T10 场景扩展。
- 验收命令：`JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test`。获一次性沙箱外运行许可，使用本地离线缓存。最终 2026-10-30 09:39:48 +08:00 BUILD SUCCESS，实际 Java 21.0.12.1；127 tests / 0 failures / 0 errors / 0 skipped（原有 116 + T08 11）。证据：target/surefire-reports/com.taxagent.harness.TaskMemoryTest.txt 及同目录全量 XML/TXT（Git 忽略）。
- 覆盖：SQLite 重建 service 恢复 WAITING_USER、新 Session 接续及补充更正、跨用户/企业任务及偏好隔离、重复 ACTIVE/WAITING_USER 拒绝、偏好 CRUD 及原话、取消/旧版本拒绝、模型迟到不覆盖取消、访问及模型返回后权限撤销、未知用户/无 Session、模型猜测不进入事实、伪造用户/跨企业工具拒绝、工具摘要保存与当前发票用途重查、缺库不创建、重复迁移不覆盖业务记录。测试只操作 JUnit 临时数据库。
- 失败与修复：首次全量 127 项出现 1 failure/2 errors，原因是不可变 Map.containsValue(null) 抛 NPE；改为 values 流判空后完整重跑全部通过。既有 Mockito 动态 agent 警告不影响通过。
- 审查与边界：三份官方原件及 Word 计划书 SHA-256 与冻结基线一致；未改冻结业务契约、初始化样例或运行数据库已有记录。Git 原有未跟踪状态保留，未清理、暂存、提交或推送。无新增依赖；真实模型未实测，当前无生产登录入口。任务已验收收尾，无遗留阻塞，无需跨会话交接。

### T09 — 安全写入与回读验证
- 状态：DONE；完成日期：2026-10-30；先保存 IN_PROGRESS 再实现与验收。已确认 T03/T04/T06 DONE、T09 TODO，读取约定文件及 Git 状态后认领，仅实施 T09。
- 范围/顺序：可信精确一次性授权 → 同事务权限/当前规则/对象复查 → 唯一操作台账和业务更新 → 回读核验 → JDK21 全量测试。无 HTTP/Agent 工具注册，不触碰运行库。
- 验收：权限矩阵、企业复合键、字段/异常输入、说明与立即保存区分、低金额主动补充、重复键一致/冲突、回滚、规则变化及未知结果只查询恢复；失败不标 DONE。回退移除新增 service/台账，已保存业务数据不得自动撤销。

- 变更：新增 tool/InvoiceWriteService.java、resources/invoice-operations.sql、InvoiceWriteServiceTest.java；RuleService 增加同连接当前规则校验，ToolContract 修复不可变 Map 判空及 null 字段名校验；更新 README、工具契约及本台账。无需额外设计文档，无新依赖。
- 授权/数据：可信入口独立签发一次性能力，绑定用户/企业/票据/精确 changes/operationId；说明不授权，空白须明确清空。仅白名单字段，复合键定位，低金额主动补用途可用，不要求备注必填。事务中复检权限及当前启用官方规则；未知语义拒绝，不猜用途。9 月以外保守拒绝，不推断历史规则。
- 原子性：操作 ID 全库唯一，与业务更新同事务；同键同内容只查询核验，异内容冲突；相同现值不 UPDATE。更新及台账插入后各回读整张发票，提交成功才 verified=true；失败回滚。超时或结果未知先 recover 查询操作台账和实际值，无自动重试，缺记录也不视为重写授权。
- 验收命令：`JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test`。获一次性沙箱外运行许可；最终 2026-10-30 09:45:33 +08:00 BUILD SUCCESS，实际 Java 21.0.12.1，145 tests / 0 failures / 0 errors / 0 skipped（原有 127 + T09 18）。报告 target/surefire-reports/com.taxagent.harness.InvoiceWriteServiceTest.txt 及全量 XML/TXT（忽略）。
- 覆盖：U001 两企业可写、U002 C001 只读/C002 无权、未知用户/企业/不存在票据、授权精确绑定与一次消费、说明与保存区分、白名单/null/空白/控制字符、明确清空、备注可选、低金额、跨企业同号、同键一致/冲突、同值不 UPDATE、权限撤销复检、规则变更/停用/版本歧义拒绝、保存完整回读、台账失败回滚、业务/台账触发器篡改回滚；真实 Future 超时后 recover 只查询、重建 service 恢复、缺记录与后续值漂移拒绝覆盖。超时测试模拟响应交付延迟，不声称验证断电或进程崩溃。
- 失败与修复：首次测试编译缺少静态导入，修复后暴露既有 InvoiceUpdate.containsValue(null) 对 Map.of 抛 NPE，改为流判空并补 null 参数回归；最终全量通过。既有 Mockito agent 警告不影响通过。
- 边界：未修改官方原件/计划书/冻结业务契约、现有种子或运行库业务数据；未对 data/harness.db 实例化写服务或迁移。仅临时测试数据库；未注册 T10 Agent 工具或 HTTP、未实施 T11。原有未跟踪文件保留；未提交/推送。任务完成，无需跨会话交接。

### T10 — 三类官方场景端到端集成
- 状态：DONE；认领人：Coding Agent；完成日期：2026-10-30。先读 TASKS、README、BUSINESS_CONTRACT、TOOL_CONTRACT、AgentRuntime、TaskMemory、InvoiceWriteService、RuleService、ReadOnlyFinanceTools 与相关测试，检查 Git（原有文件均未跟踪），确认 T05–T09 DONE 后保存 T10 IN_PROGRESS 和计划，再编码。只实施本原子任务，无多 Agent。
- 变更：新增 runtime/FinanceAgent.java、FinanceAgentIntegrationTest.java、docs/plans/active/PLAN-20261030-end-to-end.md、docs/specs/SPEC-20261030-finance-agent.md；修改 AgentRuntime、TaskMemory、OpenAiCompatibleClient 接线，更新 README、docs/index、TASKS。无新增依赖、业务表 Schema 或公网入口，旧测试保留。
- 行为：本地服务绑定可信 Session/企业，模型根据 Schema 选择查询/检查/追问/写入；补充信息只入 TaskMemory，独立可信 approvedTurn 核对本轮精确保存/清空后签发授权，模型不能提供授权/身份/verified。原只读构造器保持旧能力；显式写服务注入才暴露 update_invoice，规范嵌套 Key 引用等价展开为自包含参数。写入复用 T09 的同事务权限/规则复检、幂等及回读。授权不持久化、不跨轮复用。
- 全量命令：`JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test`。获一次性沙箱外许可，2026-10-30 09:54:26 +08:00 BUILD SUCCESS，真实 Java 21.0.12.1，158 tests / 0 failures / 0 errors / 0 skipped（旧145 + T10 13）。保留既有 Mockito/动态 agent 警告，不影响通过。
- 独立 smoke：同 JAVA_HOME 和 Maven 参数，增加 `'-Dtest=FinanceAgentIntegrationTest#scenarioOneMonthlyQueries+scenarioTwoDirectSaveAndSameOperationNeverUpdatesTwice+scenarioThreeResumeSupplementSaveAndRequery'` 后执行 test。2026-10-30 09:54:45 +08:00 BUILD SUCCESS，9/0/0/0。均使用 FakeLlmClient 脚本及实际临时官方种子 SQLite，不访问真实云模型，不声称云模型已测。
- 验收：两企业7/8/9月发票和银行收入/支出独立统计与冻结金额一致；九月缺用途5/3、明确保存F002 purpose/remarks后回读并重查为4/2。仅补充时台账0且缺口不变；重建服务和Session可恢复WAITING_USER、补充及剩余待办，重新查询实际状态。切企业/用户不能读取他人任务，跨企业工具被拒；U002 C001读成功/写拒绝、C002入口拒绝。模型伪造authorization/user_id/verified、更换授权发票、跨轮复用授权均拒绝；失败不展示模型成功声明。重复同operationId/内容在禁止二次UPDATE的触发器下核验成功，台账仍1条；recover只查询，不自动重试。
- 展示证据：target/t10-evidence/query-*.json、direct-save.json、C001.json、C002.json，含模型输入、任务、Schema、工具步骤与实际结果；可由smoke重建，均Git忽略。报告在target/surefire-reports，独立smoke覆盖该类报告为9项；全量158项结果见上述实际执行输出。模型Finish/text不是写入证据，以工具verified及回查为准。
- 验证收尾：首次全量158项已通过；检查发现写工具独立Schema需展开本地引用，修正并断言后全量再次通过；补齐查询/直接保存的结构化导出后最终全量和smoke均通过。未修改官方原件、计划书、BUSINESS_CONTRACT、TOOL_CONTRACT、规范Schema/种子或data/harness.db。原件/计划哈希匹配冻结基线；运行库检查前后SHA-256同为132acc76e4bb8ed78c78619d544005955242106edbc47aa5988b69d6ff50ec44。
- 限制：只提供可信服务层，无CLI/前端或认证HTTP；真实模型兼容性未验证。财务写入与任务进度不保证跨服务原子提交，未知结果须先recover及实时查询，不凭任务或模型文本重试。T11/T12仍TODO，未整理无关文件、未提交/推送；无需跨会话交接。

### T11 — 回归与故障注入用例（计划阶段）
- 续做认领：2026-10-30，Coding Agent 沿用 IN_PROGRESS；本轮仅修改测试及本台账，生产缺陷仅保留回归证据、不修生产代码。
- 状态：计划阶段 IN_PROGRESS（已由下方最终验收记录 supersede）；认领人：Coding Agent；计划日期：2026-10-30。计划见 `docs/plans/active/PLAN-20261030-t11-regression-fault-injection.md`。本记录仅保留最初计划范围；最终实现、测试与 DONE 结论见下方「T11 — 回归与故障注入验证记录」。
- 重点：覆盖权限、跨企业隔离、规则版本、重试/超时、空结果与失败区分；并注入“财务提交成功后 TaskMemory 保存失败”，确认任务不误报完成、发票/台账仅提交一次，之后使用只读 recover 与新鲜查询核对，不盲目重试。
- 验收：复用临时 SQLite 与 Fake 模型；先跑定向测试，再跑 JDK21 离线 Maven 全量，记录真实测试数。未改官方材料、冻结契约或运行库；不提交/推送。

### T11 — 回归与故障注入验证记录
- 状态：DONE；认领人：Coding Agent；完成日期：2026-10-30。沿用上述认领，开始前重读 AGENTS.md、README.md、TASKS.md、冻结业务契约、T11 计划及相关实现/测试并检查 Git 状态。计划文件保留原样；本条为最终实际验收记录。
- 变更文件：仅 `src/test/java/com/taxagent/harness/FinanceAgentIntegrationTest.java`、`AgentRuntimeTest.java`、`RuleServiceTest.java` 及 `TASKS.md`。新增5个执行用例（关键边界两企业参数化2个、Runtime 2个、规则1个）；无生产代码、业务Schema、API契约、官方材料或运行库修改。
- 关键提交边界：`committedInvoiceSurvivesTaskMemoryFailureWithoutReplay` 在 C001/C002 各执行一次。先保存 WAITING_USER 任务快照；临时 SQLite 的 BEFORE UPDATE agent_tasks 触发器仅在该任务且 `t11-commit` 操作已可见时 RAISE(ABORT)。可信 approvedTurn(SAVE_NOW) 的真实写工具返回 verified=true 后，任务保存抛 STORAGE_ERROR，SQLException 原因为 `t11 task persistence failure after invoice commit`；服务 load 的完整任务与故障前相同，直接 SQL 验证 status/version 均未推进。无误报完成、无正常 Turn 返回。
- 一次提交与恢复证据：临时 AFTER UPDATE invoices 计数表精确只有目标企业 F002 一行，invoice_operations 总数为1。禁止后续所有发票 UPDATE 的触发器保持启用、任务故障触发器亦未移除；重建 FinanceAgent，模型被设置为调用即失败，重复两次 recover 均返回精确内容及 verified=true，新建只读服务查询均找到当前 purpose/remarks。恢复前后任务不变、台账1、实际UPDATE计数1；跨企业 recover 拒绝，另一企业同号发票查询结果整体未变。触发器/计数表只存在于 @TempDir 测试库，随fixture清理；不声称测试了断电/进程崩溃。
- 权限及隔离：复用 `enterpriseAndUserSwitchesStayIsolatedAndReaderCannotWrite`、`TaskMemoryTest.taskAndPreferenceScopeIsUserPlusEnterprise`、`ReadOnlyFinanceToolsTest.permissionsForBothQueries/compositeKeysRemainDistinct` 和 `InvoiceWriteServiceTest.companyCompositeKeyAndOptionalRemarks`。覆盖 U002 C001 读允许/写拒绝、C002 拒绝，任务/偏好用户+企业隔离及发票/流水复合键；新增边界测试亦验证跨企业恢复拒绝。
- 规则：复用 `RuleServiceTest.officialBaselineAndTraceableContent/disabledRulesNeverFallbackAndUnknownSemanticsNeverExecute`、`InvoiceWriteServiceTest.disabledAndAmbiguousRulesRejected`、两企业三场景集成的规则引用断言。新增 `missingEnterpriseRuleDoesNotFallbackOrAffectOtherEnterprise`，移除临时库 C001 的 R002 2.0 关联后返回 UNSUPPORTED_SCOPE、不回退停用1.0，C002 仍精确使用 R003 1.0 且必补3张。
- 重试/超时：新增 `modelRetryCanRecoverButToolErrorsAreNotRetried`，模型瞬时异常后第2次决策成功；配置1次模型重试时工具存储错误仍只产生1个工具Exchange、模型收到错误后结束也不能伪报成功。新增 `lockedToolTimesOutWithoutModelRetryOrEmptySuccess`，临时库真实 EXCLUSIVE 锁阻塞读取，300ms工具限制返回 TOOL_TIMEOUT、无成功数据、模型仅调用1次；finally释放锁。复用 `modelFailureTimeoutAndBoundedRetry` 验证模型超时及重试耗尽精确2次，`callerTimeoutQueriesCommittedOperationInsteadOfRetrying` 验证调用者超时后只读回查已提交操作、消费过授权拒绝再用，`scenarioTwoDirectSaveAndSameOperationNeverUpdatesTwice` 验证同operationId在禁止二次UPDATE时仍可核验、台账仅1。
- 空结果/错误：复用 `ReadOnlyFinanceToolsTest.monthlyRecordsAndTotals/storageFailuresDoNotBecomeEmptyResults/rechecksPermissionsAndRejectsUntrustedInput` 及 `scenarioOneMonthlyQueries`。七月空集合和0仅表示成功无记录；权限拒绝、缺库/缺表仍保留错误。新增Runtime断言 STORAGE_ERROR 的 data=null、错误码保留、不展示模型“成功，金额零”，工具超时亦为ERROR。
- 定向命令：`JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" '-Dtest=FinanceAgentIntegrationTest,AgentRuntimeTest,TaskMemoryTest,InvoiceWriteServiceTest,ReadOnlyFinanceToolsTest,RuleServiceTest' test`。2026-10-30 10:47:35 +08:00，**91 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**。此前未加入缺失规则测试的定向运行90项亦通过。
- 全量命令：`JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test`。2026-10-30 10:48:08 +08:00，**163 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**，实际Java 21.0.12.1（启动日志及Surefire XML核对）。报告 `target/surefire-reports`；基线158 + 新增5。受限只读沙箱需临时目录/构建写入及本机端口，获许可后沙箱外离线执行；现有Mockito动态agent警告未影响结果。
- 结论与边界：全部T11验收通过，未发现需保留失败测试的生产缺陷，无阻塞。仅Fake模型、临时SQLite和本机HTTP stub/健康检查，未访问网络服务、真实用户数据或data/harness.db。原有未跟踪项目文件保留，Git状态仍为原先未跟踪列表；未清理、提交或推送，未开展T12/T13。

### T12 — 简易交互界面、审计日志与演示录制准备
- 状态：DONE；认领人：Coding Agent；完成日期：2026-10-30。基线 main/本地 origin/main 均为 c1bc5a14afe18bce572179fd8b335600f785f017，初始工作区干净；先核对任务、README、冻结业务契约、T10 服务契约、TaskMemory/FinanceAgent/AgentRuntime/InvoiceWriteService 和相关测试，认领 IN_PROGRESS 并创建计划后编码。
- Commit：无，按要求未提交/推送。T13 保持 TODO，未开展。未修改现有 data/harness.db、官方原件、计划书、冻结业务及 T10 服务契约、真实密钥，未清理其他文件。
- 变更：demo/DemoConfiguration、DemoSecurity、DemoController、DemoAudit；static/index.html、demo.css、demo.js；application.yml；DemoApiTest；DEMO_SCRIPT.md；T12 计划、演示 API 规范、README、docs/index、TASKS。无新依赖、无前端框架；未修改现有 Agent/工具业务规则。
- 安全：固定 operator→U001 / viewer→U002，明示“本地模拟身份，不是生产认证”；HttpSession 服务端身份、cookie HttpOnly/SameSite Strict、变更 CSRF（含选择身份）、直接 loopback + Host/Origin 检查、禁代理/CORS、默认 127.0.0.1。未知 DTO 字段及 userId 拒绝；permissions 实际复核，U002/C001 只读、U002/C002 拒绝；用户/企业任务及审计隔离。三项 LLM 配置缺任一项返回 MODEL_NOT_CONFIGURED，无 Fake fallback、无密钥记录。
- 写入：补充只存明确值/原话，普通聊天无授权；确认面板和二次弹窗显示精确企业、发票、字段/值，经 approvedTurn 后仍由模型决定是否调用 update_invoice，exact approval 保持；以真实工具 verified 与 recover 回读为证。财务提交后任务保存失败只读 recover，返回 TASK_STATE_SAVE_FAILED 和“财务写入已核验但任务状态保存失败”，记录可恢复审计；审计同时失败明确报错，不伪称整体成功，不重复写入。
- 审计：SQLite append-only demo_audit，禁止 UPDATE/DELETE/同id REPLACE；保存请求/任务/工具/callId/月或发票ID/字段名/operationId/outcome/errorCode/verified/UTC时间，不保存完整财务流水、金额、用途/备注内容或模型文本。当前 UI 用 textContent 展示真实工具调用及完整结果；历史仅脱敏摘要。审计请求失败则执行前阻断，提交后失败明确返回审计错误和可得回读证据。
- 验收命令：`JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test`。
- 最终验收：2026-10-30 12:11:31 +08:00，真实 Temurin Java 21.0.12.1，**186 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**（原有163 + T12新增23）；报告 target/surefire-reports，日志 target/t12-test.log。全部业务测试使用临时 SQLite；FakeLlmClient 仅测试。覆盖 loopback/外部拒绝、Host/Origin/CORS、固定角色/任意身份拒绝、CSRF/会话轮换、用户企业任务隔离、查询/追问/补充/恢复、未确认不写、精确确认一次写入及回读/同operation不重写、缺模型、审计追加/隔离/脱敏/防改删覆盖、提交后任务保存失败 recover，以及审计与状态双重失败。
- 环境说明：首次沙箱测试被系统临时目录限制阻止，按权限流程在沙箱外复验成功；首次离线 package 缺缓存 JAR 插件，下载 Maven 构建插件后 package 成功，不涉及模型网络调用。构建产物与依赖缓存均为 Git 忽略目录。
- 浏览器验收：ego-browser 操作真实 127.0.0.1:18080 页面，使用全新系统临时 demo DB，显式移除三项模型变量；身份选择、任务创建、缺模型提示、确认弹窗及取消、Viewer/C002 拒绝、Viewer/C001 任务隔离均通过。只读查库 invoice_operations=0，C001/F002 用途仍空，缺模型错误已审计；截图 target/t12-ui.png。临时服务已停止，浏览器测试空间已关闭，未清理临时数据。
- 演示准备：DEMO_SCRIPT.md 提供全新独立 DB 初始化/启动、查询→追问→补充→恢复→精确确认→工具 verified/回读→审计脚本、失败核查，以及 macOS Shift–Cmd–5 / QuickTime 手动录制说明。真实财务写入与回读在 Fake + 真 SQLite 自动测试中验证；本次不调用真实云模型、不生成录屏视频。真实供应商兼容性与最终手动录制为明确遗留，不能把未配置模型页面或离线测试冒称云模型演示。

### T12 — 验收后审计隔离安全修正
- 状态：DONE；认领人：Coding Agent。确认 request 在所有权校验前写入目标 UUID，上一轮将 task UUID 断言改为 requestId 检查掩盖了泄漏；本次恢复强断言并修复，仅改指定 T12 文件。
- 修复：带 taskId 的 request 在写 REQUESTED 前先以当前 DemoPrincipal 调用 agent(p).load(taskId)；NOT_FOUND/PERMISSION_DENIED 拒绝日志使用 taskId=null、保留请求ID/action/errorCode，不保存目标 UUID；适用于 load/supplement。turn 原有先 load 后记录顺序不变。
- 强断言：Viewer/C001、Operator/C002 分别对 C001 拥有者任务执行失败 load/supplement/turn 后，GET 自己的审计均不含目标 UUID；load/supplement 的 NOT_FOUND 拒绝事件 taskId=null。拥有者仍能查看自己的任务和含本任务 UUID 的审计。未以 requestId 检查替代 UUID 隔离。
- CSP：DemoSecurity 为页面、静态资源和 API（含拒绝响应）设置 `default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'`；无外部资源、无界面重做，来源/CSRF规则不变。
- 验收：2026-10-30 12:16:23 +08:00，真实 Java 21.0.12.1，`JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test`：**187 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**。T12 共24项（本次新增1项CSP测试并加强原隔离用例）；日志 target/t12-security-test.log，报告 target/surefire-reports。此前186项为历史验收，本记录为最终安全复验。
- 范围：仅 DemoController/DemoSecurity/DemoApiTest、TASKS/README/T12 plan/API文档；保留其他既有变更。仅临时 SQLite 和测试 Fake/本机 stub，未访问真实模型、未修改业务服务/数据库/官方原件或 data/harness.db、未做T13、未提交推送。现存审计表为 append-only，本次不迁移或清理任何历史记录。

### T13 — 打包提交说明及复现文档
- 本轮单一任务续认领：Coding Agent；GPT-6 Luna Max / Responses 最小适配，保留全部现有 T12/T13 工作，T13 仍 IN_PROGRESS。未查看环境变量值、未设置凭证、未发起真实 API 请求。
- Responses 实现：完整 `/responses` endpoint 显式选择 gpt-6-luna / reasoning.effort=max / store=false / parallel_tool_calls=false，复用原系统指令和工具 schema；Chat Completions 保留，无自动协议回退。LlmClient.Call 内部保存并防御性复制 output items（含 reasoning），现有 history 续接重放后追加精确 call_id 的 function_call_output；JsonIgnore 和安全 toString 防止供应商续接元数据进入可见历史/审计。AgentRuntime、DemoConfiguration、财务工具、写授权、DB schema、UI未改。
- 本轮精确验证命令：`JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home PATH=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home/bin:$PATH mvn -o -B -ntp "-Dmaven.repo.local=$PWD/.m2/repository" -Dtest=OpenAiCompatibleClientTest,OpenAiResponsesClientTest test`。2026-10-30 14:07:33 +08:00，**5 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**（Responses新增3、Chat原有2），报告 target/surefire-reports。首次编译泛型推断错误已修正；沙箱内测试5/0/3/0因本机监听拒绝，获准沙箱外重跑原命令通过。只跑上述聚焦离线测试，全部HTTP均为127.0.0.1 stub；验证请求配置/schema、调用解析、reasoning/output重放及call_id关联、最终文本、畸形/截断/多调用、缺key及关联错误零网络、重定向拒绝/脱敏错误和隐藏元数据。
- 文档同步：README、T12 API spec、DEMO_SCRIPT、REPRODUCE、T13计划及本台账；配置示例无凭证值。真实云端E2E范围已获用户授权，待本机凭证安全配置；仅使用官方合成样本+全新临时SQLite+loopback。本轮未操作SQLite、data/harness.db、真实财务数据、官方材料、计划书或冻结契约，未提交推送；未声称真实集成或全量回归通过。
- 状态：IN_PROGRESS；认领人：Coding Agent；2026-10-30。先检查Git、AGENTS、TASKS、README、DEMO_SCRIPT、冻结契约、T12计划/最终安全证据及官方参赛说明，确认T11/T12 DONE；先保存认领与T13计划再推进。仅单一T13。2026-10-30用户授权扩展为真实模型E2E：只用官方演示种子合成数据与独立临时DB；不碰 data/harness.db、不用真实财务数据、不写入真实密钥、不提交推送。当时初始目标为 gpt-6-luna + OpenAI Responses API + reasoning.effort=max；之后本轮用户改选 DashScope qwen3.8-flash + Anthropic Messages API。执行环境未读取凭证或使用聊天中暴露的密钥；真实云端E2E待用户撤销旧密钥并在本机配置轮换密钥后执行。
- 变更：新增REPRODUCE.md、RELEASE_CHECKLIST.md、docs/plans/active/PLAN-20261030-t13-release-reproduction.md；在README/docs/index追加入口；更新本台账；.gitignore仅追加安全模式。T13完整Git变更清单与原有T12区别见RELEASE_CHECKLIST。
- 副本：git ls-files -co --exclude-standard列出70个当时文件，复制到/tmp/taxagent-t13-oor9i4rc/source，含未提交T12；无data/.env/target/.m2/.git，不复制现有DB。不是全新Git checkout；使用原仓库既有隔离缓存绝对路径，未重新下载依赖。
- 实际测试命令（副本根）：`JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -o -B -ntp -Dmaven.repo.local='/Users/a123/Projects/财税 harness/.m2/repository' test`。沙箱内187/0/145/0（系统临时目录/Socket拒绝），获准沙箱外原命令重跑，**2026-10-30 12:22:41 +08:00 BUILD SUCCESS，187 tests / 0 failures / 0 errors / 0 skipped**；Surefire XML求和复核一致，java.version=21.0.12.1。日志/tmp/taxagent-t13-oor9i4rc/test-escalated.log；Maven3.10.0。相同环境加`-DskipTests package`成功，产物副本target/harness-0.0.1-SNAPSHOT.jar（忽略，不交付构建产物）。
- 初始化：副本`python3 -B scripts/official_seed.py --check`输出PASS，原件哈希与所有种子字段一致；JDK21 JAR `--initialize-data --database=/tmp/taxagent-t13-oor9i4rc/demo.db`成功。首次沙箱内SQLite原生库临时目录拒绝，获准外部重跑成功。只读查新库2企业/2用户/4权限/40发票/48流水/5规则；HTTP检查后invoice_operations=0。
- 启动：env -u LLM_ENDPOINT -u LLM_API_KEY -u LLM_MODEL移除配置，JDK21 JAR参数`--taxagent.database=/tmp/taxagent-t13-oor9i4rc/demo.db --server.address=127.0.0.1 --server.port=18083`；获准本机监听。curl健康200/UP；ego-browser真实页面可浏览、显示本地模拟身份、operator/C002任务恢复；浏览器fetch健康200、demo.js200（6562字节）、demo.css200（2653字节）；CSRF会话→创建任务→turn返回200/MODEL_NOT_CONFIGURED，无真实模型调用。HTTP脚本沙箱本机连接拒绝后通过获准curl与浏览器检查。Ctrl+C停止（进程退出130），最终curl连接失败退出7，确认无遗留服务；浏览器任务空间关闭。
- 审查：初始SHA256快照与最终比对，官方三原件、计划书、冻结契约、已有data/harness.db及所有非T13文件内容未变；原T12未提交文件完整保留。git diff --check通过；git ls-files -ci --exclude-standard无输出；data/.m2/target/.env、tmp财务CSV、exports财务XLSX、recordings视频均命中忽略。没有清理文件、提交或推送。
- 未证实/后续：为避免不必要下载，未做空缓存首次在线构建；真正全新checkout（当前HEAD尚无未提交T12）未验证，故不标DONE。用户在允许联网的独立新checkout先整合完整源码，再按REPRODUCE第2节在线test/package、离线重跑及新库UI复验，补版本/计数/结果后再验收。最低Maven版本和其他平台未实测。真实供应商兼容性及真实云模型三场景未实测；正式2–3页设计短稿、去敏关键记录、≤5分钟手动视频和最终压缩包/提交仍由用户完成。主办方截止/入口/大小待核实，不冒称比赛提交就绪。文档已记录恢复路径，无额外handoff。

### T13 — DashScope Messages 适配（进行中）
- 认领人：Coding Agent；IN_PROGRESS。仅独立适配器、loopback 测试和复现文档；保留既有改动。禁止外网和 SQLite 操作。暴露凭证须撤销，替换值仅本机输入。

## DashScope 本轮验证结果（2026-10-30）
T13 保持 IN_PROGRESS。当前目标 DashScope qwen3.8-flash，旧 Luna/Chat 支持保留。
精确命令：
```zsh
JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" -Dtest=AnthropicMessagesClientTest,OpenAiCompatibleClientTest,OpenAiResponsesClientTest test
```
初次沙箱执行因 loopback bind 被拒：7 tests / 0 failures / 4 errors；获准沙箱外离线重跑。最终 2026-10-30 14:47:51 +08:00 BUILD SUCCESS：8 tests / 0 failures / 0 errors / 0 skipped（Messages 3、Responses 3、Chat 2）。覆盖空 thinking/signature 原样重放、关联 tool_result、内部状态不序列化、请求字段、严格解析、重定向目标零访问及错误响应标记不泄漏。仅本地 HTTP stub；未读取环境凭证、未访问外网、未操作任何 SQLite。无提交或推送，既有 T12/T13 改动保留。
该次 14:47 离线验证时的超时基线为 HTTP/AgentRuntime 15 秒；以下记录本轮 Qwen 超时修正。真实 E2E 尚未执行；聊天中提供的密钥已暴露，须撤销后仅在本机配置轮换值。

### DashScope 超时与启动文档跟进（2026-10-30）
- 当前 Qwen Messages HTTP 请求与 AgentRuntime 等待上限均设为60秒；其他模型仍采用15秒默认，系统硬上限保持60秒。
- `DEMO_SCRIPT.md` 的主启动块已切换到 DashScope `qwen3.8-flash`；README、REPRODUCE、演示 API 契约及 T13 计划已同步当前目标与轮换密钥要求。
- 聚焦离线验证通过。首次尝试因 ServerFS 保留源文件旧 mtime、Maven 误用旧 class 而在测试前编译失败（0项执行）；随后仅刷新四个已改源文件的 mtime、未改内容，再运行：`JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home PATH=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin /opt/homebrew/bin/mvn -o -B -ntp -Dmaven.repo.local='/Users/a123/Projects/财税 harness/.m2/repository' -Dtest=AnthropicMessagesClientTest,OpenAiCompatibleClientTest,OpenAiResponsesClientTest,FinanceAgentTimeoutTest test`。2026-10-30 15:33:45 +08:00 **9 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**（Messages 3、Responses 3、Chat 2、Qwen timeout 1）；`git diff --check` 通过。测试只访问 loopback stub/未访问外网，未读取环境变量或凭证，未操作 SQLite；保留 target 中的 T10/T12 证据。真实云端 E2E 仍待用户撤销暴露密钥并在本机设置轮换值；范围限合成数据、独立临时 SQLite 与 loopback。

### Qwen 联调稳定性跟进（2026-10-30）
- 依据最新失败记录和 DashScope 官方 Messages 说明，Qwen 3.8 Flash 的 `max_tokens` 同时计算思考和答复；原 4096 上限可能在生成可解析答复前耗尽。提升至8192，并将 `stop_reason=max_tokens` 安全映射为 `MODEL_OUTPUT_LIMIT`。
- Messages 适配器现将无效响应、连接异常、401/403、429 和其他 HTTP 错误分别映射为安全错误码；不返回异常正文或密钥。接受一个带说明文本的合法工具调用；仍拒绝多工具、截断调用及无效参数，原写入审批/权限规则不变。UI 增加对应中文错误说明。相关 API 规格与T13计划已同步。
- 验收：JDK21离线 `mvn -o -B -ntp -Dmaven.repo.local='/Users/a123/Projects/财税 harness/.m2/repository' -DskipTests package`，2026-10-30 18:14:28 BUILD SUCCESS；生产代码20个文件及测试源码16个文件均编译，测试执行跳过。`node --check src/main/resources/static/demo.js` 与 `git diff --check` 通过。18:14以最终JAR重启本机隔离演示服务，PID 23216，`/api/health` 返回UP，页面已加载新错误说明。
- 本轮未调用真实模型、未读取或输出密钥、未调用插件、未读写SQLite；模型真实联调仍待轮换凭证后验证。8192提高单次输出上限，也提高单轮可能使用的token上限；没有自动重试。T13保持IN_PROGRESS，未提交或推送。

### T14 — 财税工作台 UI/UX 视觉重构
- 状态：DONE；认领人：Coding Agent；2026-10-30。先检查最新任务、Git、AGENTS、README、冻结契约、前端和DemoApiTest，保存IN_PROGRESS与独立计划后实施。完整证据：[T14计划](docs/plans/active/PLAN-20261030-t14-ui-ux.md)。
- 变更：仅本台账、新T14计划、static/index.html、demo.css、vendor及LICENSE/NOTICE；深色SaaS三栏、手机单列、右侧折叠检查器。Core1.4.0、Icons Webfont3.50.0固定本地资源及MIT，含Bootstrap5.3.7 MIT许可；npm SHA512核验通过，无CDN/插件/构建链。
- 验证：2026-10-30 19:12:48 JDK21.0.12.1隔离缓存离线全量194/0/0/0，含DemoApiTest24/0/0/0；精确命令见计划。29/29原DOM id保留，demo.js字节未改；HTML严格嵌套/资源路径、JS语法、CSS111规则解析、git diff --check通过；八个320–1920px断点及手机展开表单无横溢，52项本地资源HTTP200，图标字体/键盘焦点/跳转/折叠/长消息滚动通过。
- 保护：临时UI服务指向不存在的/tmp数据库，移除模型配置，仅静态与GET session；验证后正常停止并关闭浏览器。基线SHA256复核，除本任务授权文件外原文件均不变，T12/T13/Java/官方材料/冻结契约/运行数据库保留。未add/commit/push。
- 遗留：T14无阻塞；真实模型、Safari/Firefox/实体手机未验证。T13原有状态与待办不变。

### T15 — 独立 React + TypeScript 产品前端
- 状态：IN_PROGRESS；认领人：Coding Agent；开始日期：2026-10-30。
- 授权范围：仅新增 frontend/**、T15 计划及本台账追加记录；保留 T12/T13/T14 和全部既有文件。禁止 Git add/commit/push。
- 计划：[T15](docs/plans/active/PLAN-20261030-t15-react-frontend.md)。核对真实 DTO/路由后实现首页、执行工作区、审核结果；隔离数据库验收，不触碰运行库。
- 2026-10-30 跟进：React/TS 三页、typed API Adapter、对话/工具分组、审批核验和单测源码已完成；隔离合成库 API smoke 报告通过。写入锁安全修复：只有操作号匹配且回读 `verified=true` 才释放锁。用户实测 npm `verify` 四阶段通过：TypeScript、ESLint、Vitest 4/4、Vite 7.3.7 生产构建成功（1636 modules）。
- 2026-10-31 历史任务体验修复：已结束/取消的历史任务恢复后改为专用状态面板，提供“基于原任务新建查询”，仅预填目标并调用原有任务创建 API；新任务独立 ID，待办/授权/工具证据不会继承。正在进行的任务保留对话；本轮刚完成的任务仍保留实际对话结果、审查入口和独立新建入口。新增终态分类单测，但最新改动仍需重新运行 `npm run verify` 和浏览器验收，T15 保持 IN_PROGRESS。未改 Java、旧版 static 或数据库，未提交或推送。
- 2026-10-31 新建任务流程修复：检查发现首页仅调用 POST /tasks 并导航至工作区，不会将原始输入提交到第一轮 /turn；任务列表刷新失败还会把已创建的任务错误显示为整体失败。已将“创建成功后立即使用新 ID 发起首次非授权 turn”封装为 `createAndRunFirstTurn`，创建后立刻展示任务 ID、输入消息和工作区；第一轮失败仍保留任务，绝不自动重试或附带写入 approval。列表刷新失败只显示局部警告，不抹掉新任务；创建响应未知时要求先查任务历史，防重复创建。新增 3 个回归单测（首次 turn、turn 失败保持任务、创建失败不执行 turn）。
- 2026-10-31 新建任务 `CONFLICT` 根因修复：截图显示同用户/企业已有 `ACTIVE`/`WAITING_USER` 任务；`task-memory.sql` 的 `one_open_agent_task` UNIQUE INDEX 强制一次只能有一项未结束任务，原前端未提供取消入口。保留这一约束，前端新增未完成任务提示、继续任务、刷新状态、结束旧任务二次确认；已有任务时禁用新建，后端竞争导致的 409 会主动刷新任务列表并显示可操作的指引，不重复创建。为实现显式结束，补充 `POST /api/demo/tasks/{id}/cancel`：必须 CSRF、固定可信身份、确认标志、合法任务版本，TaskMemory.cancel 的作用域与乐观锁持续生效；操作经审计，不删除历史、不回滚任何财务记录；存在待核验财务操作时前端禁止取消。新增 Java 2 项回归测试（重复任务/确认/版本/待补充取消/跨用户隔离），前端 3 项回归测试（未结束任务识别、CSRF 取消、冲突不自动重试）；截至这次修改 **尚未运行最新 Maven / npm 全量测试**，T15 仍为 IN_PROGRESS。此次安全可控地扩大了原 T15 范围至 DemoController/FinanceAgent 与 DemoApiTest；未修改原始数据、未提交或推送。

### T15 — 提交前两个安全阻断修复
- 状态：IN_PROGRESS；认领人：Coding Agent；2026-10-31。仅修复跨 HttpSession 的 turn/cancel/supplement 并发与静态首页未知写入锁，同步指定文档和回归；不提交、不推送、不联网、不访问现有数据库。

### 两项安全修复最终验收（2026-10-31）
- 本轮修复状态：DONE；T15整体仍IN_PROGRESS（原有部署/浏览器等未验证项保留）。64个固定进程内条带按可信userId+enterprise映射，既有DemoController的turn/cancel/supplement共用；保留权限、作用域、CSRF、乐观版本和审计。不是跨JVM锁，不承诺强杀不响应中断的超时worker。
- 并发回归：两个独立HttpSession，同一operator/C001，CountDownLatch阻塞批准写入前的模型决策；取消和补充在释放前均不能完成；释放后turn为COMPLETED、回读verified，invoice_operations仅1条，竞争请求409 CONFLICT。finally释放latch并shutdownNow/awaitTermination。
- 默认静态页与React共用taxagent-pending-operation，仅持久化{id,taskId,unknown:true}；请求前存储失败则不发送。只有当前响应operationId精确匹配且verification.verified===true清锁；网络、JSON、HTTP错误和未核验均保留锁，重载展示警告，其他只读操作可用。Vitest直接隔离执行实际demo.js，新增7项（六类失败/不匹配及成功回读），不调用服务。
- 已先touch本轮修改的DemoController.java和DemoApiTest.java；Maven明确重新编译20个main/16个test源码（debug parameters release21），未clean、未删除历史证据。Java21命令：`mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test`，22:06:21 +08:00 BUILD SUCCESS，198/0/0/0；16份Surefire XML汇总一致，DemoApiTest28项含crossSessionMutationWaitsForApprovedTurn的cancel/supplement两项PASS。随后同参数`-DskipTests package`，22:07:20 BUILD SUCCESS。
- frontend/执行`npm run verify`：typecheck/lint、Vitest1文件18/18、build全部通过，退出0；`node --check src/main/resources/static/demo.js`与`git diff --check`退出0。初次typecheck发现测试专用Node内置模块缺类型声明，已用测试局部说明处理，不增加依赖；Vitest系统临时缓存和Maven缓存锁的沙箱权限失败获准原命令重跑成功。
- REPRODUCE/DEMO_SCRIPT当前目标max_tokens已同步8192；README数字与API取消/锁范围契约同步。未联网、未调用真实模型、未读取/打印环境变量或凭证、未操作data/或现有数据库；SQLite仅由JUnit @TempDir管理。未暂存、未提交、未推送。
- 收尾核对：与本轮开始的逐文件内容哈希比较，仅指定的10个源码/测试/文档文件变化，无删除；Git暂存区为空，已跟踪忽略文件检查为空。原有其他未提交变更完整保留。

### T15 — 损坏操作锁存储 fail-closed 补修（2026-10-31）
- 状态：IN_PROGRESS；认领人：Coding Agent。仅指定五文件；键不存在才无锁，读取/解析/形状错误使用unknown占位锁，保留原存储，不发送财务请求。静态页回归覆盖损坏JSON、getItem抛错、形状错误与只读刷新；React初始化同样保守，setItem失败阻断本页发送。仅跑npm verify、node --check和diff --check，不跑Maven/应用、不联网、不操作数据库、不提交推送。

- 本次补修验收：DONE（T15整体仍IN_PROGRESS）。静态页和React仅键缺失返回null；读取异常、损坏JSON、无效形状均生成{id:unknown,taskId:unknown,unknown:true}保守占位锁，不改写原存储、不发送财务请求。合法已有对象继续锁定。React同时修复setItem失败时不得继续发送；两页均先建立内存锁再setItem，清锁则先成功removeItem再清内存/渲染，removeItem异常保持锁。
- 2026-10-31最终npm run verify退出0：typecheck/lint/build通过，Vitest 1文件36/36（原18+本次18）；覆盖静态损坏JSON/getItem异常/无效形状后approve零请求且只读刷新可用、存储不被覆盖清除、setItem和removeItem失败保持锁，以及React纯初始化函数的缺失/异常/合法/无效形状。node --check src/main/resources/static/demo.js、git diff --check均退出0。首次Vitest被系统临时缓存沙箱权限阻断，获准原命令离线重跑通过。未运行Maven或应用、未联网、未操作数据库、未读取密钥、未暂存/提交/推送；仅指定五文件内容变更。

### T16 — 初赛评审对齐收尾（2026-11-06）
- 状态：IN_PROGRESS；承接 T15/T13。目标：让任务规划、上下文来源、自然表达、离线构建和提交材料可见、可验收；不改变业务边界。
- 已完成：
  - React 单一前端：删除旧 `static/index.html`、`demo.js`、`demo.css` 和 vendor；根路径固定跳转 `/react-app/`；`mvn package` 在 `prepare-package` 自动安装 Node/npm、构建并复制到 JAR，`mvn test` 不触发前端。
  - 检查器新增“执行计划”（由后端任务状态/工具历史推导，不是模型自述）和“上下文来源”四栏：任务进度、长期偏好、规则（仅 `query_rules` 工具结果）、企业上下文。
  - 错误码增加自然提示；`WRITE_NOT_EXECUTED`/`WRITE_NOT_VERIFIED` 文案保留证据门，不把失败说成成功。
  - 待补提示改为第一人称自然措辞，仍不展示模型“已完成”的错误结论。
  - 完整模型输入 trace：系统提示、完整用户消息、四个工具 schema、初始历史计数；React 检查器可展开。
  - OSV 依赖 CVE 扫描：35 个 runtime 依赖，Jackson 2.21.7、Tomcat 10.1.59、Log4j 2.25.5；0 未抑制，2 条 Spring 6.2.19 风险接受，见 `docs/specs/DEPENDENCY_SECURITY.md`。
  - 审计保留策略：append-only、禁止 UPDATE/DELETE、导出后整库归档，见 `docs/specs/AUDIT_RETENTION.md`；测试覆盖触发器拒绝。
  - 离线包：`scripts/package_offline.ps1` 使用已有 `frontend/dist` + `mvn -o -Dfrontend.skip=true package`；README/REPRODUCE 已写明联网首次构建、预构建 JAR 和 Node 缓存边界。
  - 最终提交说明草稿：`docs/SUBMISSION_NOTES.md`。
- 验证：Java 247/0/0/0（关闭 Windows 临时目录清理）；前端 37/37（typecheck、ESLint、Vite build）；`mvn clean package` 成功；OSV 0 未抑制；React 真实查询+R004 与完整 prompt trace 通过。
- 未完成：官方 Qwen3.8-Max 复测、React 浏览器内受控保存/跨轮、≤5 分钟视频、最终作品 zip。
- 已按用户明确要求将本轮 P1/P2 改动提交并推送到 `origin/work/zhanghengyuan1`；此前 `5b19cdd` 也已在同一分支。

### T17 — 提交前完整测试与浏览器保存闭环（2026-11-11）
- 状态：DONE（测试范围）；认领人：Coding Agent。完整证据：[TEST_REPORT_20261111.md](docs/evidence/TEST_REPORT_20261111.md)。
- 环境：macOS / JDK 21.0.12.1 / Maven 3.10 / Node 22.22.2；`127.0.0.1:18085` + 独立临时库 `/tmp/taxagent-test-AXRSwm/test.db`；模型沿用用户指定的 `qwen3.7-flash`（endpoint 路径含 `/anthropic` → AnthropicMessagesClient）。
- **缺陷修复（提交门禁阻塞项）**：`AgentRuntime` 工具结果序列化未关闭 `WRITE_DATES_AS_TIMESTAMPS`，`invoice_date` 输出 `[2026,9,8]` 数组；前端 `isInvoice()` 要求字符串，导致"审核与结果"永远拿不到发票、浏览器内受控保存不可用。修复：`AgentRuntime.JSON` 增加 `.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)`；新增回归 `AgentRuntimeTest#invoiceRowsExposeIsoDateStringsNotArrays`。仅改这 2 个文件，未动契约、规则与业务边界。
- 受控保存（API，任务 `1dc188aa`）：`operationId=cd15f309…`，F002/purpose，`verified=true` 且回读一致，`invoice_operations` 1 行含 `task_id`，审计 `fields=purpose` 无明文；同 `requestId` 重放不重复写入，改载荷重放 HTTP 409。
- 负例：viewer 读 operator 任务 404；viewer 携带批准 403 PERMISSION_DENIED；operator 口头"直接保存"无批准时只读完成、零写入。
- 浏览器 E2E（真实 Chromium）：只读查询（引用 R002 v2.0，4 张缺用途）→ 审核页 10 张发票 → 二次确认弹窗 `operationId=8a441f8a…` → 保存后 `verified=true · 数据库回读已核验`（F009）→ 检查器执行计划 6 步全绿。截图 6 张存 `docs/evidence/ui-e2e-20261111-*.png`。
- 补充表单：`F004.purpose` + 明确值 + 原话 → "仅记录信息" → 提示"明确事实已记录，发票未保存"，模型随后仍报告 F004 系统内为空。
- 回归：Java 248/0/0/0（离线）；前端 37/37 + typecheck + build；OSV 35 依赖 0 未抑制（2 条既有豁免）；`secret_scan` CLEAN；`git diff --check` 通过。
- 已知约束：补充清空待补字段后再发起查询会让任务自动结项（COMPLETED），随后同任务禁止写入；演示需在"等待补充"状态下直接审核保存。
- 未完成：官方 Qwen3.8-Max 复测（用户指定沿用 3.7-flash）、≤5 分钟视频、最终 zip、空缓存首次在线构建、跨 JVM 并发验证。
- 未提交/未推送；工作树仅本轮 2 个源码/测试文件 + 6 张证据截图 + 本报告新增。

### T18 — 演示流程按修正顺序重录（2026-11-11）
- 状态：DONE（重录+取证）；认领人：Coding Agent。记录：[DEMO_RERUN_20261111.md](docs/evidence/DEMO_RERUN_20261111.md)。
- 顺序修正：**先受控保存、后补充声明**。原脚本第 3→4 段在同一任务连演会卡死——补充清空待补字段后再查询会使任务结项，写入按钮被禁用。保存改在「等待补充」状态下执行，实测通过；补充拆为独立第二任务，只演示「仅记录声明、不写入发票」。
- 实测：任务 `020af773`，首轮查询 24.7s（4 只读工具，NEEDS_INPUT），保存轮 13.2s（update_invoice → query_invoices → check_invoice_purpose，FINISHED），`operationId=ec584656…`，`verified=true` 且数据库回读一致；F002.purpose 空→「办公室采购」，C001 缺用途 10→9，`invoice_operations` 1 行含 task_id，审计 22 条且含明文用途 0 行。取消一次（`f0304d78…`）库中零变化。
- 教训 1：待补字段必须与要写字段对齐。首轮录制用 `F002.purpose,F002.remarks`，模型多带 remarks 导致载荷不一致，被 fail-closed 拒绝 `CONFIRMATION_REQUIRED`（零写入）。
- 教训 2：保存失败会在 `sessionStorage`/`localStorage` 留下操作锁，换库重启也不释放；演示前须核查或清理。
- 产出：14 张按镜头编号的截图 `docs/evidence/demo-rerun-20261111-*.png`；DEMO_SCRIPT.md 原文未改，仅在该记录第五节给出建议补丁。
- 未提交/未推送。

### T19 — 提交前风险修复：规则生效 + 运行记录持久化（2026-11-11）
- 状态：DONE（代码+实测）；认领人：Coding Agent。记录：[FIXES_20261111.md](docs/evidence/FIXES_20261111.md)。
- **规则生效**：删除 `RuleService` 的官方正文白名单（OFFICIAL 全等比较），改为从存储正文解析 `每张必填` / `≥X 元必填` 两种既有表述；矛盾措辞或无法解析仍 `UNSUPPORTED_SCOPE`。停用旧版→导入新版后，检查结果与引用版本立即随新版本变化。单测新增 2 项（共 11 项）；真实服务实测：R002 3.0（≥1000 元）→ 缺用途只剩 F006，模型引用 R002 3.0。
- **运行记录**：新增 append-only `agent_trace` 表 + `GET /api/demo/tasks/{id}/traces`；每轮（含失败轮）记录系统提示、完整用户消息、工具名、历史条数、memorySource、outcome，不含密钥。检查器在刷新/重启后回退显示持久化记录。
- **运行时鲁棒性**：真实模型把结束协议发成 `finish` 工具调用会导致整轮 `INVALID_ARGUMENT`；现按文本协议归一化 `finish`/`ask`，仍保留"工具错误不得报成功""批准轮未核验不得结束"约束。新增 2 项测试。
- 其他：系统提示补充"无本轮批准时须说明去审核面板确认"；HTML 入口加 `Cache-Control: no-cache`（避免升级后旧 JS）；CLI 说明"更新=新增版本"。
- 不改：`amount_cents`（工具契约冻结整数分）、8 月用途检查（规则限定 9 月）。
- 回归：后端 252/0/0/0；前端 37/37 + typecheck + build；JAR 重新打包且旧哈希资源已清；18086 已用新 JAR 重启。
- 未提交/未推送。

### T20 — 模型口径纠偏：统一 qwen3.7-flash，撤销"官方评审模型 Qwen3.8-Max"表述（2026-11-11）
- 状态：DONE（文档纠偏，无代码改动）；认领人：Coding Agent。
- 触发：用户指出 `Qwen3.8-Max` 无人授权。核查结论：该表述由提交 `1837f8b`（11-06）写入 `docs/SUBMISSION_NOTES.md`，属早期会话假设；官方材料 `01_参赛说明.docx` 与 `03_业务规则.docx` 全文未出现任何模型名，只要求"赛事允许的模型 + 配置项切换"。仓库中无任何 3.8 型号的真实调用证据。
- 决定（用户 2026-11-11 明确）：**统一使用 `qwen3.7-flash`，不使用 Qwen3.8-Max**；换模型通过 `LLM_MODEL`/`LLM_ENDPOINT` 配置，不做型号复测。
- 已改：`docs/SUBMISSION_NOTES.md`（第 2 节改写为"模型与接口配置"、提交门禁、当前状态）、`docs/index.md` 索引描述、`README.md`（模型配置表、适配路径、验证说明、状态行）、`docs/agent-handoff.md`、`docs/evidence/TEST_REPORT_20261111.md`、`docs/evidence/FIXES_20261111.md`、`docs/参赛作品设计说明_2-3页.docx`（"官方评审模型复测"段改写为当前事实，247 项更正为 252 项）。
- 未改历史记录：`docs/plans/active/PLAN-20261030-*.md`、`docs/specs/SPEC-20261030-demo-api.md`、`TASKS.md` 早期条目中的 `qwen3.8-flash` 属当时自选的联调目标（仅 stub，未发真实请求），保留原文，仅在各文件顶部加口径注记。
- 未提交/未推送。

### T21 — 录制可用性收口：脚本重写 + 数字口径标注 + 交付文档对齐（2026-11-11 晚）
- 状态：DONE（文档，无代码改动）；认领人：Coding Agent。
- **DEMO_SCRIPT.md 重写**：旧版第 3→4 段"补充后再保存"同任务连演必卡死（补充清空待补字段→任务推进→再查询直接结项→写入按钮禁用）。新版改为六段：身份→查询→**等待补充状态下直接审核**→取消一次→确认+回读→检查器/审计→（可选）补充声明独立任务；待补字段只登记 `F002.purpose`；新增录制前四步（新建库、基线核对、清 `taxagent-pending-operation` 锁、显式注入密钥）；模型口径改为 `qwen3.7-flash`。
- **数字口径**：新增"口径表"章节。新建库 C001 2026-09 缺 5 张（F002/F004/F003/F006/F009）→ 保存 F002 后 4 张 → 再保存 F009 后 3 张。此前 `TEST_REPORT` 的"4 张"与 `DEMO_RERUN` 的"5 张"被误读为矛盾，实为同一库不同写入时点；三份证据均已加时点标注。
- **实测校验**：用 `--initialize-data` 新建库验证基线脚本输出 `invoices 40 / bank_entries 48 / 缺 ['F002','F004','F003','F006','F009']`；台账表在服务启动时才创建，脚本已做存在性判断。
- **REPRODUCE.md**：模型段从千帆 `deepseek-v4.1-flash-1017` 改为 DashScope `qwen3.7-flash` + `/anthropic`，并更新已验证范围与未完成项。
- **RELEASE_CHECKLIST.md**：头部状态从 2026-11-06（247 项、千帆、浏览器保存未完成）更新为 2026-11-11（252 项、qwen3.7-flash、浏览器全链路通过）；对照表更新源码/设计说明/运行记录/演示/三场景/规则管理六行；测试计数基线 187→252。
- **新增** `docs/evidence/README.md`：关键运行记录索引（四份记录 + 22 张截图 + 口径表），`docs/index.md` 同步。
- 未提交/未推送（按 AGENTS.md：不得擅自提交）。

### T22 — 合并队友分支 work/zhanghengyuan1 到 main（2026-11-12 00:15）
- 状态：DONE；认领人：Coding Agent；依据用户明确指示「帮我合并然后更新到我的 main 分支」。
- 合并对象：`origin/work/zhanghengyuan1` 6 个提交（`aa56377` SSE 流式 tool/文本、`1d11efd` Enter 发送、`b8946d6` Enter 提交目标+HTML no-store、`0e5fa49` 服务端 task plan 持久化+畸形回复重试、`ea557af`/`96b3ef5` 完成态提示调整）。
- 冲突仅 2 处，均属「同一位置各加一段」，按并集解决、未删除任何一方逻辑：
  1. `frontend/src/App.tsx:5` 类型导入——同时保留 `SavedTrace`（main）与 `ToolProgress`（work）。
  2. `AgentRuntime`——`finalizer(...)`（finish/ask 工具调用归一化）与 `emit(Consumer<Progress>,...)`（流断开不改业务结果）并列保留，各自闭合。
- 合并后语义核对：`/turn` 与 `/turn/stream` 并存；`/traces`、`/audit` 未受影响；`run(p,id,input,progress)` 重载对两条路径都保留每轮耗时、审计与 `agent_trace` 落库；两套 `SYSTEM_PROMPT` 改动均在；前端 `task.plan?.steps ?? deriveExecutionPlan(...)` 服务端计划优先、本地推导兜底。
- 验证：后端 `mvn test` 252/0/0/0 BUILD SUCCESS；前端 `tsc --noEmit` 0 错、`vitest` 37/37。
- 已知缺口（未在本轮补）：队友 6 个提交未附自动化测试，`POST /api/demo/tasks/{id}/turn/stream` 目前零测试覆盖，建议后续补一个 SSE 冒烟用例（断言末帧 `done` 携带 `verified`）。
- 文档同步：`DEMO_SCRIPT.md` 第 2 段补一句「流式呈现属正常，最终只认末帧 `done`」。

### T23 — 合并后重启验证与推送（2026-11-12 00:50）
- 状态：DONE；认领人：Coding Agent；依据用户指示「帮我把最新的代码直接推送到 GitHub main 分支」。
- 重启：停掉三个合并前旧进程（18085/18086/18084 各自绑定旧库），用 00:15 构建的合并版 JAR 在 `127.0.0.1:18084` 起新进程，库为全新临时库 `demo.db`。基线 `invoices 40 / bank_entries 48 / rules 5`，C001 2026-09 缺用途 5 张（F002/F004/F003/F006/F009），与 DEMO_SCRIPT 口径一致。
- 模型连通性：直接请求 DashScope `/apps/anthropic/v1/messages` 最小载荷 → HTTP 200、`model=qwen3.7-flash`、回复 `OK`；不落库、不回显密钥。
- SSE 路由：`POST /api/demo/tasks/{id}/turn/stream` 无会话返回 403（非 404），确认合并进来的流式端点已注册。
- 已知陷阱：`--initialize-data` 只接受 `--database=`，混用 `--taxagent.database=` 会抛 `Unknown initialization argument`；`nohup ... &` 起的进程会在命令结束后被回收，需用常驻后台任务方式持有。
- 文档：`DEMO_SCRIPT.md` 密钥注入改为主用 `set -a; . ./.env; set +a`，并补充两套参数不可混用的说明；新增 `CLAUDE.md`（贡献者命令与架构说明，密钥均为占位符）。

### T24 — 演示脚本审计段落纠错：写入记录不在列表首位（2026-11-12 10:35）
- 状态：DONE（文档纠错，无代码改动）；认领人：Coding Agent。触发：用户按 3:30 段落实操，找不到“最新一条 `update_invoice · TOOL_RESULT`”，怀疑没有审计功能。
- 核查结论：**功能存在**。前端导航 `审计记录`（`App.tsx:326`）→ `<details>`「展开真实审计记录」（`App.tsx:362`）→ 事件以 `{tool} · {outcome}` 渲染，带 ✓ 核验标记，展开显示除 id/tool/outcome/time 外的全部字段。构建产物与 JAR 内均含「审计记录 / 展开真实审计记录 / 刷新审计」文案。
- **脚本原句错误**：面板按 id 倒序，写入成功后系统还会追加「查询核对（query_invoices）」和「本轮结束（turn FINISHED）」两条，工作台任务状态再追加两条，因此 `update_invoice · TOOL_RESULT` 实测在第 4～5 条，从不是第一条。证据：`/tmp/taxagent-test-AXRSwm/test.db` 中 id 61 = `update_invoice/TOOL_RESULT/fields=purpose/verified=1`，紧随其后 62=`query_invoices`、63=`turn/FINISHED`、64/65=`tasks`。
- 已改 `DEMO_SCRIPT.md`：3:30 段落改为「按倒序找第 4～5 条」，补实测顺序表（5 行）与自查条件「列表里完全没有 update_invoice 行 = 这次没有真正写入，不得指着别的行说已保存」。
- 附带发现（未改代码）：用裸 HTTP 直接构造批准轮（`/tasks/{id}/turn` + approval）连续两次返回 `CONFIRMATION_REQUIRED`，模型 `update_invoice` 参数与批准值不严格相等即被 fail-closed 拒；UI 路径历史上可成功（库中已有 TOOL_RESULT 记录）。录制前建议走一遍真实浏览器全链路确认。

### T25 — 用途规则按存储规则选择，去除企业→规则编号硬编码（2026-11-18）
- 状态：DONE；认领人：Coding Agent。依据用户指示，为复赛“未公开的数据组合、规则变化”做准备。
- 范围：仅 `RuleService.purposeRule` 与 `RuleServiceTest`。不改 2026-09 月份限制（冻结契约 `BUSINESS_CONTRACT.md` 第 87/161 行明确要求，需用户另行决定），不改工具契约、种子或前端。
- 实现：`purposeRule` 删除 `C001→R002 / C002→R003` 映射。某规则编号只要在该企业任一已存版本正文中含“必须填写用途”即视为用途规则，再取其在该日期唯一启用版本；改写措辞的后续版本仍被拒绝（UNSUPPORTED_SCOPE），停用不回退，同编号多启用版本或多个用途规则编号同时启用均为 CONFLICT。写入前检查 `requireSupportedWriteRules` 复用同一选择逻辑。
- 新增测试 2 项：新企业 C003 + 自有规则 R107（≥500.01 元）可执行并引用 R107；C002 换用新编号 R203 后生效；R002 与 R005 同时启用为 CONFLICT，R001/R004 不被当作用途规则。
- 验证：JDK 21.0.13、Maven 离线，`RuleServiceTest` 13/13；全量后端 254/0/0/0（在 scratchpad 本地副本运行，因 /mnt 挂载盘编译极慢；源文件与工作树一致）。前端未改，未跑。
- 未改：2026-09 月份限制（冻结契约）、`docs/specs/TOOL_CONTRACT.md` 第 74 行“仅支持官方 R002 2.0 / R003 1.0”的旧描述（T19 起已不准确，属已 DONE 任务的规格文档，待用户决定是否更新）。未提交/未推送。

### T26 — 批准轮保存载荷由程序生成，模型只决定是否保存（2026-11-18）
- 状态：DONE；认领人：Coding Agent。依据用户指示。背景：T24 记录批准轮因模型 `update_invoice` 参数与批准值不严格相等而连续 `CONFIRMATION_REQUIRED`。
- 范围：`AgentRuntime`（批准轮写工具无参数，执行可信批准载荷）、`TaskMemory.remainingPending`、`DemoController` 审计字段来源、两个客户端系统提示及相应测试。不改写服务、授权、台账、证据门与前端。
- 实现：批准轮发布的 `update_invoice` 改为无参数 schema（`properties:{}`、`additionalProperties:false`）；`AgentRuntime.approvedUpdate` 对 `{}` 直接执行可信 `ApprovedWrite.update()`。兼容旧式完整载荷：仍严格解码（伪造 `verified`/`authorization` 等字段 → INVALID_ARGUMENT），且必须与批准完全相等，否则 CONFIRMATION_REQUIRED；无批准时一律 CONFIRMATION_REQUIRED、零写入。批次内写入仍拒绝，证据门、一次性授权、台账幂等、事务回读均未改。
- 连带：`TaskMemory.remainingPending` 不再解码模型参数，改以本轮 verified 结果的键与各字段值对齐批准为证据；`DemoController` 审计在批准写入时从可信批准取 `invoice_id`/`fields`（保持 `fields=purpose` 演示口径）；两个客户端系统提示补充“批准轮以空参数 {} 调用，由服务端保存已批准的精确修改”（保留 `OpenAiResponsesClientTest` 断言的原短语）。模型历史中的调用参数保持原样（两客户端续接校验要求回放与模型原始参数一致）。
- 新增测试 3 项：空参数批准写入 verified、用途/备注与批准一致、待补字段清空、台账 1 行；无批准空参数 CONFIRMATION_REQUIRED 零写入；API 层空参数写入的审计记录 `invoiceId=F002`、`fields=purpose` 且无明文用途。更新 1 项：写工具 schema 断言改为无参数。
- 验证：JDK 21.0.13 离线全量后端 257/0/0/0（scratchpad 副本，`diff -rq src` 与工作树一致）。前端未改、未跑。**真实模型（qwen3.7-flash）未复测**：需在浏览器重走一次受控保存，确认模型按新提示以 `{}` 调用；旧式完整载荷仍兼容。未提交/未推送。

### T27 — 复赛契约修订草案（2026-11-18）
- 状态：DONE（草案）；认领人：Coding Agent。依据用户指示，读取 `赛题二：财税Agent Harness系统设计与实现复赛要求.zip`（PDF 3 页，SHA-256 见草案）后起草。
- 产出：`docs/FINAL_ROUND_CONTRACT.md`（状态：草案，待用户批准）；`docs/index.md` 新增一行。未修改 `docs/BUSINESS_CONTRACT.md`、`AGENTS.md`、`CLAUDE.md`、`TOOL_CONTRACT.md` 及官方材料。
- 批准：用户 2026-11-18（“approved, use your defaults”），六项待确认按默认值确定；截止时间与大小限制仍未知。
- 同步：草案改为已批准；`docs/index.md` 状态 completed；`AGENTS.md`、`CLAUDE.md` 硬约束指向复赛契约；`TOOL_CONTRACT.md` 第 31/52/64 行加 A2 注记（代码仍限 2026-09，未假装已取消），第 74 行改写为 T19/T25 后的实际行为；下方拆分 T28–T37。未改 `BUSINESS_CONTRACT.md` 与官方材料。未提交/未推送。

## 复赛任务池（依据 `docs/FINAL_ROUND_CONTRACT.md`，2026-11-18 拆分）

每项先认领再实施，一次一项；验收须实际运行，真实模型验证与自动化测试分开记录。建议顺序：T28 → T29 → T30 → T31 → T32，其余可并行。截止时间未知，按主办方通知调整。

### T28 — 取消用途检查月份硬限制（契约 A2）
- 状态：DONE（2026-11-18，Coding Agent）。范围：`ToolContract` PurposeCheck 月份校验、`RuleService.requireSupportedWriteRules`、`InvoiceWriteService` 内部 2026-09 范围、相应测试与系统提示。
- 验收：官方基线（C001 九月 5 张、C002 3 张）不变；导入生效于 2026-10 的用途规则后十月检查可执行并引用该版本；8 月仍因无适用启用规则返回 UNSUPPORTED_SCOPE（停用 R002 1.0 不回退）；写入同样按日期选规则。
- 实现：删除 `PurposeCheck` 的 2026-09 校验与 schema `const`（改为与 Query 相同的 yyyy-MM pattern）；删除 `requireSupportedWriteRules` 的月份判断。`checkInvoicePurpose` 改为每张发票按其日期当日的用途规则判断（与写入一致），按日期缓存；月内任一发票无适用规则即整体 UNSUPPORTED_SCOPE（不静默跳过）；无发票的月份按月首日引用规则或拒绝；返回本月实际用到的全部规则引用。`InvoiceWriteService` 原本即按发票日期调用，无需改。
- 验收实测（自动化）：官方九月基线不变（C001 5 张、C002 3 张，`officialBaselineAndTraceableContent` 通过）。**8 月按企业区分**：C001 无适用启用规则 → UNSUPPORTED_SCOPE；C002 由 R003 1.0（生效 2026-08-01）覆盖，缺用途仅 F016（1000.01 元；F011/F018 为 999.99 元不必填）——此前为月份硬拒绝，现为依规则可执行，属 A2 预期行为变化。十月无发票时引用 R002 2.0、缺 0 张。R002 3.0 于 10-15 生效时 10-20 发票按 3.0 判断，10-05 发票无规则覆盖 → 整月拒绝。写入：C002 F016（8 月）verified，C001 F016（8 月）UNSUPPORTED_SCOPE 零台账新增。
- 测试：新增 4 项（RuleService 2、InvoiceWriteService 1、Schema 用例 1），改 3 处旧断言（RuleService/ToolContract/ToolSchema 的 8 月拒绝）。JDK 21 离线全量后端 261/0/0/0（scratchpad 副本，`diff -rq src` 一致）。前端未改、未跑；真实模型未复测。
- 同步：`CLAUDE.md`、`TOOL_CONTRACT.md` 第 31/52/64/74 行、`FINAL_ROUND_CONTRACT.md` 注记改为已实现。
- 遗留设计问题（未改，待用户决定）：同一规则编号两个启用版本仍一律 CONFLICT（初赛契约“不推定版本优先级”）。因此“月中换版本”只能先停用旧版，导致新版生效前的同月发票无规则覆盖而被拒。若复赛要支持无缝换版，需在复赛契约中明确优先级（如“同编号取生效日最晚的启用版本”）。未提交/未推送。

### T29 — 文件上传与解析（契约 B1–B3，“文件处理”）
- 状态：DONE（2026-11-18，Coding Agent）。拆为 T29a 后端（存储、解析、导入、API、测试）与 T29b React 上传面板，两部分均已完成；已知缺口见 T29a 末条。
- T29a 实现：
  - 依赖：POI 5.5.1（xls/xlsx/docx）、PDFBox 3.0.8（文字型 PDF）；commons-lang3 覆盖为 3.18.0（OSV）。
  - 存储 `file-store.sql`（`FileService` 构造时可加迁移）：`uploaded_files`（企业、名称、版本、SHA-256、类型、大小、上传者、时间、原文 BLOB；`UNIQUE(enterprise_id,sha256)` 防重，`UNIQUE(enterprise_id,name,version)`；触发器禁止 UPDATE/DELETE）、`file_chunks`（带位置的文本段）、`record_sources`（导入记录 → 文件与“工作表!第N行”）。
  - `FileService.upload`：先检查 WRITE 权限，再按扩展名＋文件头判定类型（不符即 UNSUPPORTED_SCOPE），解析成功才落库。同企业同内容 → DUPLICATE 零新增；同名不同内容 → 新版本，旧版保留。Excel 按**表头文字**定位发票/流水表（需同行含“发票编号＋开票日期”或“流水编号＋交易日期”，列名去括号与空白，支持常见别名），不依赖工作表位置；无“企业编号”列时归当前企业。行级：非法金额（超两位小数不四舍五入）、日期、方向、缺必填、文件内重复键 → 整个文件 REJECTED、零落库；其他企业行 → skipped（ENTERPRISE_MISMATCH）；已存在键 → conflicts（EXISTING_RECORD），**不覆盖**。所有非空行另存为可引用文本段。Word 按段落/表格行、PDF 按页与行号分段；无文字层 PDF → REJECTED（NOT_TEXT_PDF）。上传文档只作知识，不成为可执行规则。
  - 兼容官方工作簿的 ISO 日期单元格（`t="d"`，POI schema 不支持）：解析前仅把工作表中该属性改写为字符串单元格，带 200MB 解压上限（此预处理绕过 POI 自身的 zip bomb 检查）。
  - API：`POST /api/demo/files`（multipart，CSRF，按会话企业；同一用户/企业互斥锁；审计 `file-upload`）、`GET /api/demo/files`、`GET /api/demo/files/{id}`（段落及位置；他企业文件视同不存在）。`DemoSecurity`：仅该路径放宽到 10MB＋64KB，且必须声明 Content-Length（否则 411）；Spring multipart 上限 10MB/11MB 兜底。
- T29a 验收实测（自动化）：官方 `02_示例数据.xlsx` 上传到新库为 C001 → 导入 0、冲突 44、跨企业跳过 44；**删除 C001 全部发票/流水后从该 Excel 重导：20 张发票、24 条流水，8/9 月行数与合计和种子完全一致，九月缺用途仍为 F002/F004/F003/F006/F009**，F011 来源 `发票!第6行`。改名重传同内容 → DUPLICATE；新版本与旧版并存；同内容可作为另一企业的文件；查看者上传两企业均 PERMISSION_DENIED；跨企业 detail → NOT_FOUND；伪装扩展名、txt、空文件、坏 zip 均拒绝；`../../etc/规则.pdf` 名称净化为 `规则.pdf`。新增 FileServiceTest 8 项、DemoApiTest 1 项（CSRF、411、413、查看者 403、审计）。JDK 21 全量后端 270/0/0/0；OSV 51 依赖 0 未抑制。
- T29a 未完成 / 已知缺口：① 工具结果尚未带出来源位置（`record_sources` 已存，模型答复引用工作表/单元格需在 T32 或后续扩展 `query_invoices`）；② 文本段尚无检索工具（T32）；③ 上传不能新建企业：契约 A1 写了“导入新的合成企业”，但第 3 项已定上传不能新增用户/权限，无权限的企业不可用——如需新企业须经可信配置，二者措辞待用户确认；④ 仓库 `.m2/repository` 尚无 POI/PDFBox，按 README 离线构建前须先联网构建一次；⑤ `mvn package`（含前端）与真实模型未跑；⑥ SSE 抑制理由已失效，见 `DEPENDENCY_SECURITY.md`。未提交/未推送。
- T29b 实现：顶部导航新增“资料文件”页（`FilesPage`）。选择文件 → “上传并解析”；结果区按服务端返回逐项说明（已导入 / 重复内容未新增 / 未导入），列出需修正的问题、同编号未覆盖、他企业未导入的行（工作表·行号·编号＋中文原因，最多 200 条）；文件列表显示名称、版本、类型、大小、上传者与时间；“查看内容”显示按原文位置分段的文本与 SHA-256 前缀。只读成员可浏览，上传控件禁用并说明原因。上传前客户端只做扩展名/空文件/10MB 预检，服务端仍全量校验；响应丢失时刷新列表并提示“结果未确认，重复上传相同内容不会产生新数据”，不自动重试。`api.ts` 新增类型、`upload()`（FormData，带 CSRF，不手设 Content-Type）、`files()`、`file(id)`、`describeUpload` 等纯函数与 4 个错误码文案。样式限定在 `.files-page` 下，表格在窄屏内部横向滚动。
- T29b 验证：Node 22.23.5（官方校验和通过）在 scratchpad 副本 `npm ci` 后 `npm run verify`：typecheck、ESLint、Vitest **41/41**（新增 4 项）、vite build 均通过。用该构建打包 JAR（`-Dfrontend.skip=true`，JAR 内含新 bundle 与 POI/PDFBox），新建临时库、`127.0.0.1:18090`、未配置模型：
  - 真实 HTTP（curl）：官方 Excel → IMPORTED、新增 0、冲突 44、跨企业跳过 44、152 段；改名重传 → DUPLICATE；官方规则 docx → 37 段；无 CSRF 403；11MB 文件 413 `REQUEST_TOO_LARGE`；查看者上传 403、可列出 2 个文件；库内仍为 40 张发票 / 48 条流水。
  - 真实浏览器（Playwright 1.56.1 + Chromium headless）：选身份 → 资料文件 → 上传合成十月 Excel（F101、F102 新票＋一行伪造 F002）→ 页面显示“新增发票 2 张…1 条与已有记录同编号，未覆盖”，问题行 `发票 · 第6行 · F002`；查看内容显示 `发票!第5行` 带表头的分段；再次上传 → “重复内容，未新增”；切换只读成员上传控件禁用；390px 宽无横向溢出；控制台 0 错误。数据库核对：F101 123450 分、F102 260000 分及来源行已入库，F002 仍为 80000 分、原销售方、用途为空。
  - 截图：`docs/evidence/ui-files-20261118-{1-files,2-uploaded,3-detail,4-viewer,5-phone}.png`（合成数据，截于“只读成员上传框变暗”样式修正之前；修正后已重跑 verify，未重新截图）。
  - `frontend/dist`（gitignore，`scripts/package_offline.ps1` 依赖它）已替换为本次验证通过的构建，原为不含资料页的旧构建。
- T29b 未做：拖放上传、上传进度条；真实模型下“上传后提问”的问答链路依赖 T32。范围：原入口上传 Excel / Word / 文字型 PDF；文件表记录上传者、企业、时间、内容哈希、版本；Excel 发票/流水导入业务表（只补缺失键，同键冲突逐条报告，不覆盖）；Word/PDF 分段入知识库并保留文件、版本、段落/页定位。只能写入上传者有修改权限的企业；不能新增用户/权限。
- 验收：同一文件重复上传零新增；同名改内容为新版本且旧版可查；无权企业上传被拒；导入后金额查询正确并能指向工作表/单元格；越权或同键覆盖尝试有明确错误码与审计。

### T30 — Agent 配置管理（契约 A5、B4，“Agent 管理”）
- 状态：DONE（2026-11-18，Coding Agent）；真实模型未复测。范围：Agent 定义（名称、职责、提示词、模型配置引用、允许的 Tool/Skill/知识、适用企业）存于 SQLite 并可经原入口增改停用；运行时按会话选定 Agent；有效权限 = 用户权限 ∩ Agent 能力；配置变更只影响之后开始的轮次。
- 验收：不改代码新增第二个只读 Agent 并生效；只读 Agent 收不到写工具，伪造调用被拒；进行中轮次不受配置变更影响；模型输入 trace 记录所用 Agent 与配置版本。
- 实现：
  - 存储 `agent-config.sql`：`agent_definitions`（id、version、JSON 定义、enabled、changed_by/at；触发器禁止 UPDATE/DELETE，编辑与启停都追加新版本，最高版本生效）；`task_agents`（任务 → Agent 绑定，T30 前的任务无记录即用默认 Agent）。`AgentDirectory` 构造时只播种一次默认 Agent `finance-assistant`（全部能力、全部企业、`default` 模型配置），重复迁移不改写。
  - 定义（`Spec`）：名称、职责、提示词（≤4000 字）、模型配置名、可选模型名覆盖、能力（6 个工具的子集，禁止只有 `update_invoice`）、适用企业。修改需对会话企业及 Agent 覆盖的**每家**企业有修改权限，配置不能扩大用户权限。
  - 运行：`FinanceAgent(…, ModelProfiles, Agent)`——提示词以 `[Agent id vN · 名称]` 头部追加在固定系统提示之后（“narrows, never overrides”），因此持久化的 `agent_trace.system_prompt` 即记录所用 Agent 与版本；工具 = Agent 能力 ∩ 运行时工具；无 `update_invoice` 时运行时不注入写服务、`approvedTurn` 在调用模型前 PERMISSION_DENIED；无 `search_knowledge` 时不注入检索服务。`DemoController` 每轮开始时按任务绑定解析当时版本（停用或不覆盖该企业 → PERMISSION_DENIED），本轮内不再变化。
  - 模型配置 `ModelProfiles`：`default` = `LLM_*`，命名配置 = `LLM_<NAME>_*`；`LlmClient.forAgent(role, model)` 由两个真实客户端实现为带追加提示词与可选模型名的副本；未配置名称 → MODEL_NOT_CONFIGURED，不回退。`DemoConfiguration` 的 `LlmClient` bean 改为 `ModelProfiles` bean（`DemoApiTest` 相应改为取 `default` 配置，断言不变）。
  - API：`GET /api/demo/agents`（Agent、模型配置名/是否已配置/模型名、可选能力；不含密钥与 endpoint）、`GET /agents/{id}/history`、`POST /agents {id, spec}`、`POST /agents/{id}/enabled {enabled}`（CSRF、互斥锁、审计 agent-save / agent-enable / agent-disable）；`POST /tasks` 可带 `agentId`（先校验可用再建任务并绑定）；`GET /tasks/{id}` 增加 `agent`。
  - 前端：导航“Agent 配置”页（卡片显示能力、企业、模型配置、提示词与版本历史；操作员可新建 / 编辑为新版本 / 启停，表单先按服务端同样规则预检，新建时默认不勾选写能力；只读成员仅查看）；首页任务输入框下“由谁处理”选择（仅启用且覆盖当前企业的 Agent）；工作区标题显示绑定的 Agent 与版本。保存失败时表单保持打开，不丢输入。
- 验收（自动化）：AgentConfigTest 7 项——默认 Agent 只播种一次；第二个只读 Agent 经配置创建、编辑为 v2、停用为 v3，历史 3/2/1，停用后 resolve 拒绝，表行不可改/删；查看者不能配置，任何人不能覆盖自己无修改权的企业，非法编号/未知能力/只写/超长提示词/非法配置名均拒绝，不覆盖 C002 的 Agent 在 C002 不可用；**只读 Agent 的批准保存在调用模型前即拒绝（模型零请求）**，普通轮伪造 `update_invoice` 不被发布且执行报错，台账 0；只含 query_rules 的 Agent 发给模型的工具只有 query_rules，调用 query_invoices → INVALID_ARGUMENT；Agent 头部、职责与提示词出现在系统提示中，命名配置 `SECOND` 实际被调用，未配置的 `MISSING` → MODEL_NOT_CONFIGURED；**本轮进行中修改为 v2 不影响本轮（仍可用 query_invoices），下一轮按 v2 只剩 query_rules 且头部为 v2**。DemoApiTest 新增 1 项（HTTP 列表/保存/绑定/只读拒绝批准保存/停用后拒绝/历史/审计/查看者 403）。全量后端 **286/0/0/0**；前端 Vitest **46/46**、typecheck、lint、build。
- 验收（真实 JAR＋浏览器，未配置模型）：表单新建 finance-reader（写能力默认未勾选）→ 卡片 v1 · U001；首页选择该 Agent 建任务 → 工作区显示“只读查询员 v1”，首轮如实报 MODEL_NOT_CONFIGURED；库中 `task_agents` 绑定 finance-reader，持久化 trace 含 `[Agent finance-reader v1 · 只读查询员]`，工具为 5 个只读工具、无 update_invoice；控制台 0 错误。截图 `docs/evidence/ui-agents-20261118-{1-list,2-task}.png`。`frontend/dist` 已更新。
- 未做 / 局限：真实模型未跑（提示词效果、命名配置的真实连通需用密钥联调）；“多 Agent 协作”（一个任务内由多个 Agent 分工、相互移交）未实现，目前是多 Agent 共存、按任务选择；Skill 概念与能力的独立版本/启停属 T31；Agent 页的企业选项沿用前端固定的 C001/C002 列表（与身份选择一致），服务端仍校验。未提交/未推送。

### T31 — 能力注册、版本与启停（契约 B5，“能力组织与复用”）
- 状态：DONE（2026-11-18，Coding Agent）；真实模型未复测。范围：Tool、Skill（可复用的提示/流程片段＋所需 Tool 组合）与知识资料统一登记版本和启停状态；同一能力可被多个 Agent 引用；停用后不发给模型、调用返回明确错误码。
- 验收：同一 Tool 被两个 Agent 使用；停用一个 Tool 后两个 Agent 均不可用且有错误码；Skill 按任务组合 Tool；变更影响可在界面或 CLI 查看。
- 实现：
  - 存储（并入 `agent-config.sql`）：`tool_status`（全局工具启停历史，只追加，最新一行生效，无记录 = 启用）、`skill_definitions`（技能版本不可改，最高版本生效，启停亦为新版本）；触发器禁止 UPDATE/DELETE。`CapabilityRegistry` 构造时只播种一次两个示例技能（`purpose-gap-check` 用途缺口检查、`bank-evidence` 银行资料解释），未被 Agent 引用前不生效。
  - 工具版本：代码内置契约版本（query_invoices / query_bank_entries / query_rules / search_knowledge 1.0；check_invoice_purpose 2.0（T28）；update_invoice 2.0（T26））。
  - 组合规则：Agent 定义新增 `skills`（旧定义无该字段视为空，7 参构造保留）。每轮可用工具 = (Agent 工具 ∪ 启用技能的工具) ∩ 已启用工具；技能只能含查询工具，**保存能力只能直接授予 Agent**；技能说明以 `[Skill id vN · 名称]` 块追加在 Agent 角色之后，trace 因此记录所用技能版本；停用或缺失的技能不生效并列入 `droppedSkills`。快照在每轮开始时取（`FinanceAgent` 构造），与 T30 一致：修改从下一轮生效。
  - 错误码：新增 `CAPABILITY_NOT_GRANTED`（HTTP 403）与 `CAPABILITY_DISABLED`（HTTP 409）。运行时：未知工具名仍 INVALID_ARGUMENT；已知但未授予 → NOT_GRANTED；已停用 → DISABLED（工具结果与本轮错误码均可见）；批量中的写工具/未知工具仍 CONFIRMATION_REQUIRED。批准轮：Agent 无保存能力 → NOT_GRANTED（T30 为 PERMISSION_DENIED，已改）、保存被全局停用 → DISABLED，均在调用模型前。
  - 权限：工具开关与技能修改是全局的，需对**所有**企业有修改权限（U001）；查看只需当前企业读权限。
  - API：`GET /api/demo/capabilities`（工具：版本/说明/状态/最后修改人/使用它的 Agent；技能：定义与使用者）、`POST /capabilities/tools/{name}/enabled`、`POST /capabilities/skills`、`POST /capabilities/skills/{id}/enabled`、`GET /capabilities/skills/{id}/history`（审计 tool-enable/disable、skill-save/enable/disable）；`GET /agents` 增加每个 Agent 的 `effective`（实际可用工具、被停用工具、生效与未生效技能）。
  - 前端：导航“能力中心”页（工具表含版本、状态、使用者与启停；技能卡片含工具、使用者、说明、版本历史与新建/编辑/启停；知识区说明规则 CLI 与资料文件启停并可跳转）；全局启停前弹窗列出受影响的 Agent 与生效时机；Agent 表单可勾选技能，Agent 卡片显示技能与“实际可用”能力（被停用者划线标注，含来自技能的工具）。
- 改动的既有断言（有意，语义更清楚）：`AgentRuntimeTest` 未暴露的 `update_invoice` → CAPABILITY_NOT_GRANTED（其余未知名称仍 INVALID_ARGUMENT）；`AgentConfigTest` 规则顾问调用 query_invoices → NOT_GRANTED、只读 Agent 批准保存 → NOT_GRANTED；`DemoApiTest` 同上（HTTP 仍 403）。
- 验收（自动化）：CapabilityTest 5 项——query_rules 版本 1.0、使用者为两个 Agent；停用后两个 Agent 均不再收到该工具，调用返回 CAPABILITY_DISABLED，重新启用后恢复，状态历史 2 行且不可改/删；停用 update_invoice 后批准保存在调用模型前 CAPABILITY_DISABLED、台账 0；**无自有工具、只含技能的 Agent 恰好获得技能的 3 个工具，系统提示含技能块**；技能 v2 下一轮只剩 check_invoice_purpose 且提示为 v2；停用技能后下一轮工具为空、提示无技能块、`droppedSkills` 记录，历史 3/2/1；未授予调用返回 CAPABILITY_NOT_GRANTED；技能不能含 update_invoice、非法编号、无工具均拒绝，查看者不能改开关/技能，未知工具 NOT_FOUND，Agent 引用不存在的技能 INVALID_ARGUMENT，查看者可读注册表。全量后端 **291/0/0/0**；前端 Vitest **49/49**、typecheck、lint、build。
- 验收（真实 JAR＋浏览器，未配置模型）：能力中心停用 query_rules，确认弹窗显示“将影响 1 个 Agent：finance-assistant…调用会返回 CAPABILITY_DISABLED；进行中的一轮不受影响”，`tool_status` 记 U001、审计 tool-disable REQUESTED+COMPLETED；表单新建只含技能 purpose-gap-check 的“用途检查员”，卡片“实际可用”为检查用途缺口、查询发票（query_rules 已停用）；以其建任务 → 持久化 trace 含 `[Skill purpose-gap-check v1 · 用途缺口检查]`，提供给模型的工具仅 check_invoice_purpose、query_invoices；控制台 0 错误。截图 `docs/evidence/ui-capabilities-20261118-{1-tools,2-agents}.png`（截于“技能来源工具的停用标注”与“刷新按钮不换行”两处小修正之前；修正后已重跑 verify）。`frontend/dist` 已更新。
- 未做 / 局限：真实模型未跑；工具版本是代码内置的（随代码发布变更，不能在界面“升级”工具本身）；技能尚不能按任务临时挑选（通过选择不同 Agent 组合）；规则的增改停仍走 CLI（已有，T06/T19），界面只读说明。未提交/未推送。

### T32 — 知识检索与溯源（“知识库策略”）
- 状态：DONE（2026-11-18，Coding Agent）；真实模型未复测。范围：`search_knowledge` 只读工具，对启用的规则与知识分段做简单文本检索（允许 BM25/关键词，不强制向量库），返回来源文件、版本、位置；停用资料彻底排除。
- 验收：同一问题 5 种问法召回同一依据；新版本导入后立即切换；停用资料从结果与模型输入中消失。
- 实现：`KnowledgeService.search` + 只读工具 `search_knowledge`（`KnowledgeQuery` DTO、schema、`AgentRuntime` 单调用与批量预检、两个客户端系统提示、`DemoController` 审计白名单）。检索范围：当前企业在指定日期（缺省当天）启用且已生效的规则（按行分段）＋上传文件中每个文件名**最新版本**且“作为知识”启用的分段；停用规则、停用文件、旧版本不检索、不回退。算法：字符二元组＋英数词 BM25，查询与文档用同一份小型财税同义词表（酒店→住宿、出差→差旅、不超过/最多→上限、价税合计→金额等）与停用词归一化；同分保持原文顺序；片段 ≤400 字；无命中返回空列表，系统提示要求此时说明资料不足。`AgentRuntime` 未传入 `KnowledgeService` 时不发布、不执行该工具。
- 知识启停：`file_knowledge_status`（无记录 = 启用，文件本身仍不可改）；`FileService.setKnowledgeEnabled`（需 WRITE，他企业文件 NOT_FOUND）；`POST /api/demo/files/{id}/knowledge {enabled}`（CSRF、互斥锁、审计 knowledge-enable/disable）。`StoredFile` 增加 `knowledgeEnabled`、`latest`。前端：文件列表显示“检索中 / 已停用 / 不检索（旧版本）”与启停按钮（仅操作员、仅最新版本）；检查器“上下文来源”新增“知识检索”栏，只取本轮成功的 search_knowledge 结果。
- **修复 T29 遗留的跨企业泄露**：官方工作簿含 C002 的发票行与汇总行，原先作为 C001 文件的文本段存储，C001 的只读成员 U002（无 C002 权限）可经检索读到。现解析时跳过表格中企业编号为其他已存在企业的行，以及任一单元格等于其他企业编号的行；仅在说明文字中提及“C002”字样的行保留（无 C002 数据）。同时：公式单元格改用 Excel 保存的计算结果（原重新求值在 `说明` 表产生 `#VALUE!`，若出现在金额列会误判为非法金额）；只有记录行带列名标签，表下说明行保持原文；ISO 午夜时间显示为日期。
- 验收（自动化）：KnowledgeServiceTest 5 项——R004 1.0 回答“银行流水能否证明付款”，C001 检索不出停用的 R002 1.0 与 C002 的 R003，8 月日期检索不到 9 月生效的 R002 2.0；**5 种问法**（“出差住酒店每晚最多报多少钱”“住宿费报销上限是多少”“差旅住宿标准”“出差住宾馆一天能报多少”“酒店费用最高多少”）首条均为《差旅报销制度.docx》v1 第2段；上传 v2 后立即命中 600 元、v1 不出现，停用 v2 后无任何文件命中且不回退 v1，重新启用后恢复；Excel 行“F011 的开票日期和金额”命中 `发票!第6行`；C002 检索不到 C001 文件，U002 检索 C001 结果中无 C002 记录行；无关问题返回空。FileServiceTest 新增 2 项（公式缓存值、跨企业行不入库）。FinanceAgentIntegrationTest 新增 1 项（模型经 search_knowledge 取得 R004 及来源；跨企业参数 PERMISSION_DENIED）。全量后端 **278/0/0/0**；前端 verify：Vitest **43/43**、typecheck、lint、build。
- 验收（真实 JAR＋浏览器，未配置模型）：新库上传 v1、v2 → v1 显示“已被新版本取代 · 不检索（旧版本）”；停用 / 启用 v2 写入 `file_knowledge_status`（changed_by=U001），审计 knowledge-disable/enable 各 REQUESTED+COMPLETED；控制台 0 错误。截图 `docs/evidence/ui-knowledge-20261118-{1-versions,2-disabled}.png`。`frontend/dist` 已更新为本次构建。
- 未验证 / 局限：**真实模型（qwen3.7-flash）未跑**——模型是否主动调用 search_knowledge 并按来源引用需联调；检索为字面＋同义词表，表外的同义说法（如“房费”）可能召回不稳，扩表需同时加测试；每次检索全量扫描当前企业分段（演示规模可接受，大文件多时需索引）；`docs/evidence/ui-files-20261118-*.png` 截于本次修复之前，其中文本段内容不受影响。未提交/未推送。

### T33 — 长期记忆生命周期（契约 B6，“长期记忆”）
- 状态：DONE（2026-11-19，Coding Agent）；真实模型未复测。范围：新旧冲突以最新明确陈述覆盖；删除/失效记忆从存储与模型输入彻底清除（审计仅脱敏元数据）；个人记忆与企业记忆分开存放与授权；可选有效期。
- 验收：更正偏好后旧值不再出现在模型输入；删除后重启也不出现；跨用户、跨企业不可见。
- 实现：
  - 范围（分表存放、分开授权）：`USER_ENTERPRISE`＝原 `agent_preferences`（增列 `expires_on`、`updated_at`，可加迁移）；`PERSONAL`＝`personal_preferences`（本人跨企业，**不得含 C/F/T+三位数字形式的企业、发票、流水编号**，防止一家企业的事实被带入另一家）；`ENTERPRISE`＝`enterprise_preferences`（企业共享，读需该企业读权限，写需修改权限）。
  - 覆盖策略：同一范围同名 → 最新明确陈述覆盖，旧值不再提供给模型；同名跨范围 → 越具体越优先（本人·本企业 > 个人 > 企业共享），模型输入只含胜出者，键名带 `个人·`/`企业·` 前缀以示来源；名称禁止含“·”。
  - 有效期：可选 `expiresOn`（不得早于今天）；到期记忆在下一次访问时**按删除处理擦除**，不是仅隐藏。
  - 彻底清除：删除/到期时 `PRAGMA secure_delete=ON`（更正覆盖时同样开启）使旧字节不留在数据库文件；并在该范围的 `agent_trace.user_message` 中按 JSON 键结构（不是按值做文本搜索）把该记忆的每个历史值替换为 `"[已删除的记忆]"`——只追加触发器仅在该事务内临时解除并立即恢复（DDL 常量由 `TaskMemory.TRACE_NO_UPDATE` 与 `DemoAudit` 共用）。控制器删除时同步清掉该用户（企业范围则该企业）的 turn 缓存，防止重放带回。审计只记录 `memory-put-*`/`memory-delete-*` 元数据。
  - API：`GET /api/demo/memories`、`POST /memories {scope,name,value,userRequest,expiresOn}`、`DELETE /memories/{scope}/{name}`；原 `/preferences` 端点保留（即 USER_ENTERPRISE），删除同样清缓存。前端“长期记忆”对话框：范围选择（企业共享仅操作员可选）、有效期、每条显示范围/有效期/是否生效（被覆盖者标注且变灰）、删除，表单先按服务端同样规则预检。
- 过程中发现并修复的缺陷：
  1. **（隐私）前端切换身份后，长期记忆对话框仍显示上一身份的记忆**（服务端未泄露，浏览器内存残留）；现切换身份与每次打开时先清空。浏览器复测：只读成员仅见企业共享一条、无删除按钮、不可选企业范围。
  2. **（稳定性）登录后页面并行请求偶发 HTTP 500 `AUDIT_STORAGE_ERROR`**：`DemoAudit` 写入事务先读（权限检查）后写，并发时 SQLite 无法升级读锁且不等待 `busy_timeout`；T30 新增的登录时 Agent 列表请求使其显现。修复：审计写入改用 `transaction_mode=IMMEDIATE`（先取写锁，会等待）；另把 T29–T31 新服务的建表/播种改为每库每进程只执行一次且带 `busy_timeout`（`tool/Migrations`）。新增并发回归测试（24 个并行 GET）：修复前 3/3 失败，修复后 5/5 通过。
  3. 保存后清空表单发生在后续刷新请求之后，期间输入会被抹掉；改为保存成功后立即清空。
  4. 对话框内的错误被页面级横幅挡在遮罩后不可见；现于保存按钮正上方显示（可访问的 alert）。
  5. 迁移脚本按 `;` 切分，注释中的分号会产生空语句导致初始化失败；已改注释并注明。
- 验收（自动化）：MemoryLifecycleTest 6 项——更正后模型输入只有新值、重启后仍为新值；三范围同名时按优先级只给模型胜出者，逐个删除后正确回退到企业共享；U001 个人记忆跟随到 C002，C001 的企业/本人记忆不进入 C002，U002 只见企业共享且不能改删，同名个人记忆互不影响；个人记忆拒绝 F002/C001/T013 等编号（本人·本企业可含），名称含“·”、过去日期、非日期、空原话均拒绝；到期记忆被物理删除；**删除与更正后数据库文件字节中不再含旧文本**。DemoApiTest 新增 2 项：删除个人记忆后（含“重启”新控制器）历史 trace 中该值被替换为 `[已删除的记忆]`、未删除的记忆保留、只追加约束恢复、审计无明文，只读成员可读不可写企业记忆；并发登录请求全部 200。全量后端 **299/0/0/0**；前端 Vitest **51/51**、typecheck、lint、build。
- 验收（真实 JAR＋浏览器，未配置模型）：操作员依次保存企业共享/个人/本人三条同名 style → 列表显示本人一条生效、其余两条“被更具体范围的同名记忆覆盖”；切换只读成员 → 仅见企业共享一条；个人记忆输入 F002 → 对话框内提示且未发送请求；审计无 ERROR、控制台 0 错误（连续两轮）。截图 `docs/evidence/ui-memory-20261118-{m1-operator,m2-viewer,m3-guard}.png`（m1/m2 截于对话框内错误提示位置调整之前）。`frontend/dist` 已更新。
- 未做 / 局限：真实模型未跑；跨范围冲突只按名称判断，语义相近但名称不同的旧要求（如 style 与 tone）不会自动识别为冲突；个人记忆的编号拦截是格式规则，不能识别不带编号的企业事实；已更正（未删除）记忆的旧值仍保留在当时的运行记录中（运行记录如实反映当时模型输入），删除时才一并擦除。未提交/未推送。

### T34 — 上下文压缩与原文回溯（契约 B7，“上下文管理”）
- 状态：IN_PROGRESS（2026-11-19，Coding Agent）。范围：长对话与大文件下的投影策略（保留任务对象、用户限制、待办）；只读回溯工具按需取回被裁剪的对话、文件片段、工具结果。
- 验收：30 轮含更正的脚本对话后，模型输入仍含企业、月份、待办与最新更正；大文件问答通过回溯取回原文定位。

### T35 — 多轮办理补强（“多轮办理”）
- 状态：IN_PROGRESS（2026-11-19，Coding Agent；与 T34 待验证并行，按用户指示只写代码，测试由用户在 Mac 本地运行）。范围：修复“补充清空待补字段后再查询即结项、写入按钮禁用”的陷阱；局部更正只改动对应授权范围并保留剩余待办；歧义时追问。
- 验收：补充后仍可在同任务受控保存；局部更正后其余待办与已批准范围不变；取消任务后不再写入。
- 实现（代码已写，**未运行任何测试**，待用户 Mac 本地验证后才可标 DONE）：
  - 根因：`supplement` 把字段移出 `pending` 即视为办完，但声明不等于保存；下一轮模型 `finish` 时 `pending` 为空 → `COMPLETED` → 审核页禁用写入。
  - `TaskMemory.Task` 新增 `awaitingSave`（旧任务 JSON 无此字段即为空）：补充的发票字段（`<发票号>.purpose|remarks`）进入“待保存”；只有本轮可信批准写入 `verified=true` 且键/值与批准一致才移出。`pending` 或 `awaitingSave` 非空时模型 `finish` 改为 `WAITING_USER`，追问列出“已记录但尚未保存：F002.purpose＝“…””，提示审核页确认保存、更正或取消。非发票字段（如 `month`）沿用原行为。
  - 局部更正：已保存后再补充同一字段只把该字段重新放回 `awaitingSave`，其余 `pending`/`awaitingSave` 不变；批准保存的值与此前声明不同时，以批准值（最新明确陈述）覆盖 `supplied`，批准清空则删除该声明。取消任务后 `continueTask`/`supplement` 均 CONFLICT，零台账。
  - `TaskPlan`/前端 `deriveExecutionPlan`：有待保存时“保存并回读核验”为 waiting 并列出字段，结项步骤显示“等待确认保存或取消”。`ContextProjector` 投影保留 `awaitingSave`；`AgentRuntime` 上下文说明加一句“awaitingSave 不得说成已保存”。前端任务面板与上下文来源显示“待保存”。
  - 测试（未运行）：`TaskMemoryTest` 改写 `modelFinishCannotComplete…AndCanResumeAfterSupplement`（原断言即陷阱行为），新增 `correctionReopensOnlyItsFieldAndKeepsTheRestOfTheWork`、`cancelledTaskRefusesTheApprovedSaveAndWritesNothing`；`DemoApiTest.modelFinishCannotCloseTaskWithPendingFieldsAtHttpBoundary` 末段由 COMPLETED 改为 WAITING_USER + awaitingSave；前端 `api.test.ts` 新增 T35 计划用例。
  - 验证后待办：同步 `DEMO_SCRIPT.md` 第 250 行“补充后再查询会让任务结项”与 `CLAUDE.md` 演示顺序坑；“歧义时追问”目前只覆盖确定性追问（待补/待保存字段），未做自然语言歧义识别。

### T36 — 异常恢复补强（“异常恢复”）
- 状态：IN_PROGRESS（2026-11-19，Coding Agent；与 T34/T35 待验证并行，只写代码，测试由用户在 Mac 本地运行）。范围：明确重试策略（只读可重试、写入只走 verify）；连续报错熔断与如实反馈；`/turn/stream` SSE 测试（末帧 `done` 携带核验结果、中途断开不改业务结果）；`AnthropicMessagesClient` 与 OpenAI 客户端畸形回复纠正一致。
- 验收：故障注入覆盖模型超时、连续错误、响应丢失、进程重启；已完成操作不重做，待确认操作不执行，回复说明真实状态。
- T33 遗留给本任务：除审计外，`TaskMemory`、`FileService.upload`、`AgentDirectory.save`、`InvoiceWriteService` 等写事务同样“先读后写”，并发写时可能 SQLITE_BUSY 后 fail closed（业务上安全但体验差）；应统一改为 IMMEDIATE 写事务并补并发写测试。
  - 核查（2026-11-19）：代码中已统一——所有 `mode=rw` 连接都经 `tool/Sqlite.open`（`transaction_mode=IMMEDIATE`），只读事务走 `openForRead`/`mode=ro`；`DemoApiTest.parallelReadsAndWritesDoNotFailOnStorageLocks` 已含并发任务创建与记忆写入。本任务未再改动。
- 实现（代码已写，**未运行任何测试**，待用户 Mac 本地验证后才可标 DONE）：
  - 重试策略（`AgentRuntime`）：运行时不重试模型；只读工具在超时或 `STORAGE_ERROR` 时重试一次（`readWithRetry`），业务拒绝（权限、参数、范围、NOT_FOUND）不重试；`update_invoice` 永不重试——写入返回 `STORAGE_ERROR`（结果可能未知）时立即结束本轮，不再询问模型，结果只能经 verify/recover 判定。
  - 熔断：同一轮连续 3 次工具错误（`MAX_CONSECUTIVE_TOOL_ERRORS`）返回 `TOOL_ERRORS_REPEATED`；中间任一成功即清零。
  - 如实反馈：`AgentRuntime.honest` 按本轮工具证据生成失败回复——已有 verified 保存则“不会重做”；写入已派发但无核验则“结果未知，勿重复保存，请核验”；写入被明确拒绝或未派发则“没有修改任何发票”；附只读查询次数及模型类错误“不会自动重发”。错误码不变，仅文本更具体。前端 `friendlyError` 补齐 `MODEL_RESPONSE_INVALID / AUTH_FAILED / RATE_LIMITED / TRANSPORT_ERROR / HTTP_ERROR / OUTPUT_LIMIT`、`TOOL_ERRORS_REPEATED`、`STORAGE_ERROR`。
  - 畸形回复一致：`LlmClient.CORRECTION` + `httpFailure`；Messages、Chat Completions、Responses 三种协议对无法解析的回复都纠正重试一次（纠正说明放在 system/instructions），再失败统一 `MODEL_RESPONSE_INVALID`；HTTP 401/403→`MODEL_AUTH_FAILED`、429→`MODEL_RATE_LIMITED`、其他→`MODEL_HTTP_ERROR`，均不重试；OpenAI 适配器新增 `MODEL_TRANSPORT_ERROR`。行为变化：Responses 此前不纠正、Messages 此前不纠正。
  - SSE：`DemoController.turnStream` 的 `complete` 改为不抛异常（客户端已断开时）。
  - 测试（未运行）：新增 `runtime/MalformedReplyConsistencyTest`（三协议×纠正一次/二次失败/HTTP 映射）、`runtime/RetryPolicyTest`（只读重试与不重试、`honest` 文本）、`FaultRecoveryTest`（模型超时、熔断与清零、写入结果未知立即停止且不重试、丢失响应＋重启不重做、未执行的批准重启后失效）；`DemoApiTest` 新增 `streamEndsWithDoneCarryingTheVerifiedResult`、`clientDisconnectMidStreamDoesNotChangeTheBusinessOutcome`（经 `MockAsyncContext` 的 `AsyncListener.onError` 模拟断开）。改动既有断言：`OpenAiResponsesClientTest` 200 畸形由 `MODEL_FAILURE` 改为 `MODEL_RESPONSE_INVALID`；`FinanceAgentIntegrationTest.approvedFinishWithoutWriteFailsClosed` 由精确文本改为前缀＋“没有修改任何发票”。
  - 风险：熔断可能改变既有测试中“同一轮连续 ≥3 次工具报错”脚本的错误码（未逐一核实）；SSE 断开测试依赖 Spring 对 `AsyncListener.onError` 的处理，若 Mac 上不稳定请反馈。

### T37 — 复赛交付文档与创新对比（契约 B8）
- 状态：TODO。范围：根目录 `作品使用说明.md`（启动、配置 Agent/能力、上传、对话、查看业务数据变化/执行记录/模型输入）；`自主创新说明.md`，候选题目为 T26“批准轮保存载荷由程序生成”，需用 `qwen3.7-flash` 在同一任务下分别运行 T26 前（上一提交）与 T26 后版本，记录通过次数/运行次数与失败码。
- 验收：评委按说明可独立完成全部操作；创新对比为真实运行记录，不是推断。
