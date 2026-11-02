# Tax Agent Harness — 开发交接说明

> 更新时间：2026-11-02  
> 用途：交给下一位队友继续开发。本文记录当前工作树、已完成能力、真实验证边界和推荐接手顺序。**不是比赛最终交付证明，也不代表当前 Git 工作区已提交。**

## 1. 一句话结论

项目已经具备可运行的财税 Agent 主链：模型决策、只读财务工具、规则检查、跨轮任务记忆、精确授权写入、写后回读、未知结果只读核验，以及静态页 / React 两套 UI。

本轮重点完成了三项安全闭环修复：

- **P0-1：** 任务不能仅凭模型说“已保存”就完成；必须有本轮可信写入结果和精确回读证据。
- **P0-2：** 写入结果未知时，提供按 task / operation 精确绑定的只读核验；失败不自动重写。
- **P0-3：** 支持 Anthropic Messages 单轮多个 tool_use 的结构化解析、批量预校验和历史回放。

真实 Qwen 的只读 API 流程（查询 → 必填字段追问 → 用户补充 → 跨轮恢复完成）已通过。但**完整真实浏览器 UI 流程尚未通过**：三项只读工具成功后，最终模型总结阶段发生 MODEL_TIMEOUT。受控写入及 UI 核验尚未做真实浏览器 E2E。

## 2. 接手先读

建议按以下顺序阅读：

1. `AGENTS.md`：仓库开发约定、保护边界。
2. 本文：当前交接快照与剩余事项。
3. `README.md`：架构、运行方式和模块职责。
4. `docs/BUSINESS_CONTRACT.md`：官方业务边界，不能擅自修改。
5. `docs/specs/TOOL_CONTRACT.md`：工具 Schema、身份、权限、字段和错误码。
6. `docs/specs/SPEC-20261030-finance-agent.md`、`SPEC-20261030-task-memory.md`、`SPEC-20261030-demo-api.md`：服务、任务记忆和演示 API 契约。
7. `TASKS.md`：任务状态和逐项验收证据。
8. `REPRODUCE.md`、`DEMO_SCRIPT.md`、`RELEASE_CHECKLIST.md`：构建、演示与交付边界。
9. `docs/index.md` 中列出的业务契约、工具契约和阶段计划：作为后续开发的唯一正式文档来源。仓库外的本地 `审计docs/` 审计快照含重复材料和过期状态判断，本次交接提交刻意不纳入；不要把它当作远端的正式文档。

## 3. 当前项目结构与主链

- 后端：Java 21、Spring Boot 3.5.16、Maven、SQLite。
- 模型协议：DashScope Anthropic-compatible Messages（当前目标 Qwen3.8 Flash）；保留 OpenAI-compatible Responses / Chat Completions，按 endpoint 显式选择，不自动回退。
- 前端：原生静态演示页 `src/main/resources/static/index.html + demo.js`；独立 React + TypeScript 工作台在 `frontend/`。
- 主链：`DemoController → FinanceAgent → TaskMemory / AgentRuntime → LlmClient → 财税工具`。
- 工具：`query_invoices`、`query_bank_entries`、`check_invoice_purpose`、`update_invoice`。金额计算和业务规则由程序执行，不依赖模型心算。
- 关键实现：`InvoiceWriteService` 管理事务、权限、精确字段、幂等台账和回读；`DemoAudit` 记录脱敏审计；`TaskMemory` 管理任务状态和待补字段。

## 4. 已完成能力（当前工作树）

### P0-1 — 写入完成证据门控

- 模型的自然语言、Finish 决策、参数或旧轮历史都不能证明保存成功。
- 只有当前轮可信授权对应的 `update_invoice` 工具结果，且 `verified=true`、发票键和值与批准内容精确匹配，才可解除对应待补字段并允许完成。
- 缺少证据时保持 ACTIVE，并返回安全错误；普通只读查询仍可正常完成。

### P0-2 — 未知写入结果自助只读核验

- 核验端点：`POST /api/demo/tasks/{taskId}/operations/{operationId}/verify`。
- 核验受 task、user、enterprise、operation 精确绑定；使用只读事务，不执行更新。
- 仅精确 VERIFIED 且持久化锁成功清除后才解除前端操作锁；NOT_FOUND、DIVERGED、异常或审计失败均保留锁。
- 静态页和 React 均有恢复后手动核验能力；不会自动重试写入。
- `invoice_operations` 仅新增可空 `task_id` 关联列；旧记录不猜测回填。

### P0-3 — 多工具调用和回放

- Anthropic Messages 最多接受 8 个 tool_use，检查 ID 唯一、参数结构和 stop_reason。
- 按原始 assistant 消息一次回放，并将多个 tool_result 放在同一 user 消息中，逐项校验 ID、名称、参数和 provider continuation。
- AgentRuntime 整批预校验后串行执行只读工具；混合包含 `update_invoice` 的批次拒绝，不放宽写入授权。
- 本地 stub / 单元测试通过；**供应商真实响应中同一轮多个 tool_use 的兼容性仍需验证**。

### T13 — Qwen 只读 API 验证和延迟适配

- 真实 Qwen API 两轮流程已通过：第一轮查询三项只读工具后，服务端门控返回 NEEDS_INPUT / WAITING_USER；结构化补充后，第二轮再次查询并完成 COMPLETED。
- 根据浏览器真实请求在工具成功后 MODEL_TIMEOUT 的证据，最新源码将首个模型请求设为 `output_config.effort=medium`，有工具结果历史的续接请求设为 `low`；max_tokens=8192，现有 60 秒上限不变。
- 该 effort 调整已通过 stub 回归和全量测试，但**尚未在重启后的真实 Qwen 服务中复验**。

## 5. 验收证据（截至 2026-11-02）

| 项目 | 最近结果 | 说明 |
|---|---:|---|
| Java 定向测试 | 88 / 0 / 0 / 0 | Anthropic adapter、超时、Runtime、批量工具、Demo API、Finance Agent |
| Java 全量测试 | 223 / 0 / 0 / 0 | JDK 21，Maven 离线 |
| Maven package | BUILD SUCCESS | 当前 JAR 为 34,131,390 bytes |
| 前端 `npm run verify` | Vitest 71 项通过 | Typecheck、ESLint、测试、Vite build 均通过 |
| 静态脚本 | `node --check` 通过 | `src/main/resources/static/demo.js` |
| Git whitespace | `git diff --check` 通过 | 最近一次文档修改前后均需复查 |
| 真实 Qwen API 只读跨轮 | 通过 | NEEDS_INPUT → supplement → COMPLETED；未执行 update_invoice |
| 真实浏览器 UI | 未通过完整链路 | 页面、会话、任务、消息和三个只读工具成功；最终模型回复 MODEL_TIMEOUT |
| 真实写入 / operationId UI 核验 | 未做真实浏览器 E2E | 仅自动化回归覆盖 |
| 空缓存首次在线 / 全新 checkout | 未验证 | 既有隔离 Maven 缓存下验证成功 |

建议复验命令：

```bash
# JDK 21 环境
mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test
mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" -DskipTests package

cd frontend
npm run verify
```

最近 JAR：`target/harness-0.0.1-SNAPSHOT.jar`，34,131,390 bytes。新 JAR 在最后一次 effort 调整后已打包，但当时没有重启 18084 现有进程；**不要假设当前运行服务已经加载最新 JAR**。

## 6. 真实模型 E2E 当前状态

最后一次浏览器只读测试使用独立 Chrome profile 和官方合成数据库：

- 服务地址：`http://127.0.0.1:18084`。
- 合成 DB：`/tmp/taxagent-qwen-e2e-20261102-08e66a4/demo.db`（仅为原开发 Mac 的本机路径，不随 GitHub 仓库分发；队友 clone 后通常不存在，若不存在请按 REPRODUCE.md 初始化新的独立合成库）。
- 身份：`viewer / C001`。
- 浏览器已成功加载页面、JS、CSS 和字体；favicon 404 不影响核心页面。
- UI 创建任务并发送一轮消息；`query_invoices`、`query_bank_entries`、`check_invoice_purpose` 均成功。
- 最终模型总结阶段超时，任务状态 ACTIVE、pending=`F002.purpose`，未形成 question，未执行 supplement 或第二轮。
- 任务 ID 后缀：`46c64d16`。若同一临时 DB 仍存在，先恢复该任务，不要再创建第二条同身份/企业任务；若 DB 不存在则重新初始化全新合成库并重新记录。
- 前后发票/流水快照一致，行数 20/24，SHA256 不变；F002 purpose/remarks 仍为空，`invoice_operations=0`。没有调用 `update_invoice`，没有任何财务写入。

另一次真实 Demo API（非浏览器 UI）已通过完整只读跨轮流程，任务完成状态和审计结果正确。两种验证不可混为一谈。

## 7. 建议队友按此顺序继续

### 第一步：接管前保护现场

- 先运行 `git status --short`、`git diff --check`，保存当前状态。
- 不要 `git reset --hard`、`git clean`、覆盖文件或从 HEAD 重建工作树。
- 不要把当前工作树误认为干净 checkout；现有代码和文档仍有未提交内容，见第 9 节。
- 确认 18084 进程实际加载的 JAR；如需重启，先确认用户允许并保留现有临时 DB。

### 第二步：完成真实浏览器只读跨轮链路

- 使用最新 JAR 启动本机 loopback 服务；密钥只在本机安全终端输入，绝不写入聊天、日志或仓库。
- 优先恢复现有 ACTIVE 合成任务（若临时 DB 仍存在），验证最新 medium → low effort 行为。
- UI 应明确展示 F002.purpose 追问；通过结构化 supplement 提供清楚标记为合成测试的信息；再发起只读续接。
- 断言 WAITING_USER → COMPLETED、工具时间线、任务版本和审计事件；超时不要盲目重发模型请求。
- 前后只读比较发票、流水、F002 字段及 operation 台账，确保没有财务数据变化。

### 第三步：验证供应商批量 tool_use

- 先用 loopback HTTP stub 验证单轮 2–3 个 tool_use、多个 tool_result 的请求回放、ID 关联和失败路径。
- 再用真实 Qwen 请求确认供应商确实会在同一响应中返回多个 tool_use；若模型只逐次返回单工具，明确记录“适配器支持，供应商真实批量行为未证实”。
- 不要通过放宽参数校验或权限检查来让测试通过。

### 第四步：真实受控写入与自助核验 UI E2E

- 仅在官方合成样本、全新独立临时 SQLite、loopback 服务上进行。
- 使用 Operator 身份；用户当前轮明确确认企业、发票、字段和值。普通聊天、任务恢复或 supplement 不能授权写入。
- 只执行一次。遇到超时或响应未知时，保留 operationId，走只读 verify/recover；禁止自动重试或新 operationId 覆盖未知结果。
- 断言工具 `verified=true`、数据库回读精确匹配、台账唯一、审计成功；再通过 UI 的 operationId 核验入口复核。
- 前后快照和操作台账必须能说明发生了什么；不得用模型文本作为保存证据。

### 第五步：交付复现与比赛材料

- 在真正全新 checkout / 空 Maven 缓存下做首次在线 test/package，再在同一缓存下离线复跑；记录 JDK/Maven 版本和准确测试数。
- 完成 2–3 页设计说明、去敏运行记录、≤5 分钟真实演示视频和最终文件白名单检查。
- 不将 `data/`、`.env`、真实密钥、运行数据库、`.m2/`、`target/`、临时导出或未审阅日志截图加入交付包。

## 8. 不可突破的安全边界

- 只使用官方合成数据和独立临时 SQLite；禁止访问或覆盖 `data/harness.db`、真实客户数据。
- 演示身份只是本地模拟，不是生产认证；服务只监听 `127.0.0.1`，不得暴露公网。
- 身份、企业、权限必须由服务端可信会话确定；不能由模型工具参数或自然语言伪造。
- 发票只允许写 `purpose`、`remarks`；必须有本轮精确确认。
- 未知写入结果只能只读核验，不能盲目重试。
- 审计不记录完整财务流水、金额或用途/备注正文。
- 现有锁仅保证单 JVM，不是分布式锁；不可宣称生产级。
- 不读取、输出或提交任何 API key、.env、凭证或私人数据。

## 9. Git 工作区与未提交内容

交接提交前的 Git 快照（用于说明本次交接提交的来源）：

- 分支：`main`；上游：`origin/main`。
- 基线 HEAD：`6af75cebbf60205375db81af6a5ff25ec212d829`。
- 已修改但未暂存：24 个受跟踪文件。
- 未跟踪状态项：3 个（`docs/agent-handoff.md`、一个测试文件、`审计docs/` 目录）。
- 暂存：0。
- 本次交接提交已包含上述 24 个修改文件，以及 `docs/agent-handoff.md` 和 `MultiToolCallTest.java` 两个新文件；`审计docs/` 刻意排除，避免重复及过期的审计快照进入正式文档树。

24 个已修改文件：

- `DEMO_SCRIPT.md`、`README.md`、`RELEASE_CHECKLIST.md`、`REPRODUCE.md`、`TASKS.md`、`docs/index.md`。
- `frontend/src/App.tsx`、`frontend/src/api.ts`、`frontend/tests/api.test.ts`。
- `src/main/java/com/taxagent/harness/demo/DemoController.java`。
- `src/main/java/com/taxagent/harness/runtime/AgentRuntime.java`、`AnthropicMessagesClient.java`、`FinanceAgent.java`、`LlmClient.java`、`TaskMemory.java`。
- `src/main/java/com/taxagent/harness/tool/InvoiceWriteService.java`。
- `src/main/resources/invoice-operations.sql`、`static/demo.js`、`static/index.html`。
- `src/test/java/com/taxagent/harness/DemoApiTest.java`、`FinanceAgentIntegrationTest.java`、`InvoiceWriteServiceTest.java`、`TaskMemoryTest.java`、`runtime/AnthropicMessagesClientTest.java`。

两个新文件已随本次交接提交纳入 GitHub 版本历史：

- `docs/agent-handoff.md`（本交接说明）。
- `src/test/java/com/taxagent/harness/runtime/MultiToolCallTest.java`（P0-3 批量工具回放回归测试）。

`审计docs/` 目录包含 21 个本地审计文件，其中部分与正式 `docs/` 重复，且设计评审仍保留已修复问题的旧结论；因此本次不推送该目录。原目录保留在本地，不做删除。

## 10. 完成交接的验收标准

- 队友能从本文和 README 独立启动、运行 Java / 前端测试。
- 能区分 API 真实模型通过与浏览器 UI E2E 未通过这两种证据。
- 能恢复或重新建立隔离合成任务，不会误操作现有数据库。
- 能解释 P0-1 / P0-2 / P0-3 的安全边界，并在修改时保留回归测试。
- 所有未完成事项仍保持可见；只有证据完整后才把 T13 / 后续任务标为 DONE。
