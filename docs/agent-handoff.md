# Tax Agent Harness — 开发交接说明

> 更新时间：2026-11-19（新增第 12 节：`作品使用说明.md` 评委视角走查待改清单）  
> 上次全面更新：2026-11-02  
> 用途：交给下一位队友继续开发。本文记录当前工作树、已完成能力、真实验证边界和推荐接手顺序。**不是比赛最终交付证明，也不代表当前 Git 工作区已提交。**

## 1. 一句话结论

项目已经具备可运行的财税 Agent 主链：模型决策、只读财务工具、规则检查、跨轮任务记忆、精确授权写入、写后回读、未知结果只读核验，以及单一 React UI（静态页已移除）。

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
- 模型协议：百度千帆 OpenAI-compatible Chat Completions（当前目标 DeepSeek-V4.1-flash-1017，`LLM_PROTOCOL=openai-chat`）；保留 Anthropic Messages / OpenAI Responses，按 endpoint/协议显式选择，不自动回退。
- 前端：唯一 React + TypeScript 工作台在 `frontend/`；`mvn package` 通过 `frontend-maven-plugin` 构建并复制到 JAR 的 `static/react-app/`，根路径固定跳转 `/react-app/`。
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
- React 有恢复后手动核验能力；不会自动重试写入。
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
| 前端构建 | `npm ci` + `npm run build` | Maven `frontend-maven-plugin`（prepare-package） |
| Git whitespace | `git diff --check` 通过 | 最近一次文档修改前后均需复查 |
| 真实 DeepSeek API | 通过 | 查询+R004、受控保存、跨轮补充保存 |
| 真实浏览器 | 查询+R004、受控保存通过 | Chrome + Playwright；浏览器内跨轮未验证 |
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
- 本轮变更已提交/推送到 `origin/work/zhanghengyuan1`；后续改动仍需先保存工作树状态。
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
- `src/main/resources/invoice-operations.sql`。
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

## 11. 2026-11-06 本分支补充（已提交/推送）

- 模型：百度千帆 Token Plan 个人版，OpenAI 兼容 Base URL `https://qianfan.baidubce.com/v2/tokenplan/personal`，模型 `deepseek-v4.1-flash-1017`，`LLM_PROTOCOL=openai-chat`；客户端自动追加 `/chat/completions`，超时 60 秒。密钥只在本机 `.tools/model.env`，不入库、不写入本文件。
- 适配真实供应商：DeepSeek 一轮返回 3 个 `tool_calls`（`parallel_tool_calls=false` 未生效）已按 `Batch` 执行；工具结果后的自然语言总结按 `Finish` 接受，但不构成写入证据。
- 新增：`query_rules`/R004 引用；按本轮批准发布写工具；`ContextProjector` 记忆投影；审计 `ruleVersion/memorySource/durationMs` 与 scorecard；偏好 CLI/HTTP API/React 入口；turn 级 `requestId` 幂等；跨标签页操作锁；限速/请求体上限；审计 CSV 导出；Maven Wrapper；React 单一入口 `/react-app/`（静态页移除、Maven 自动构建）；完整 `promptTrace`；审计保留策略；OSV 依赖 CVE 扫描（0 未抑制，2 条风险接受）。
- 真实 E2E：千帆 API 查询+R004、受控保存、跨轮补充保存通过；真实 Chrome/React 查询+R004 与完整 prompt trace 通过；浏览器内保存/跨轮、≤5 分钟视频和最终包未完成。证据见 [REAL_E2E_20261106.md](evidence/REAL_E2E_20261106.md)。
- 验证：关闭 Windows 临时目录清理后 Java 247/0/0/0；默认清理模式 1 个临时目录错误；前端 37/37；`mvn clean package` 成功（自动构建 React）。
- 模型口径（2026-11-11 定稿）：本项目统一使用 `qwen3.7-flash`，不使用 Qwen3.8-Max。官方材料未指定模型名，适配器不硬编码模型名，Anthropic/OpenAI-compatible 路径均可用；换模型只需改 `LLM_MODEL`/`LLM_ENDPOINT`，无需型号复测。
- 已按用户明确要求提交并推送到 `origin/work/zhanghengyuan1`；未合并 `main`，等待队友评审。

## 12. 2026-11-19 验收走查：`作品使用说明.md` 待改清单（9 条）

验收 Agent 按 `作品使用说明.md` 以评委视角完整走过一遍：真实浏览器（Chrome via ego-browser）+ 真实模型 `qwen3.7-flash` + 全新种子库 + 当前工作树构建的 jar。以下每条都附实测证据，可直接据此改文档或改 UI。**行号对应走查当时的 `作品使用说明.md`（165 行版本）。**

### 会影响评委操作的（按优先级）

**1. 第 119 行（§5.1）示例任务目标缺年份 — 照抄必报错（最高优先级）**

- 现文：`例如"查询九月发票与银行收支，并检查发票用途"`；首页输入框占位符同样是 `查询九月发票与银行收支，并检查发票用途…`。
- 实测：使用不含年份的表述建任务，模型自行猜成 **2025-09**，`check_invoice_purpose` 返回 `UNSUPPORTED_SCOPE`，整轮 ERROR。审计留痕：`check_invoice_purpose · TOOL_ERROR · UNSUPPORTED_SCOPE · month=2025-09`。
- 附带问题：R001 1.0 正文明确要求「用户没说清月份且前文没有明确月份时，应先追问，不能默认替换为当前月份」，但模型没追问而是猜了一个年份。
- 改法：示例改为 `查询 2026 年 9 月的发票与银行收支，并检查发票用途`，并加一句「月份请带年份」。同时建议改 `frontend/src/App.tsx` 的输入框占位符。首页那三个建议卡片写的是 `2026-09`，是安全的，可作参照。

**2. 第 85 行（§3）首页 Agent 选择器在全新库上不存在**

- 现文：`在工作台新建任务时，在输入框下方的 Agent 选择器中选用哪个 Agent，任务就绑定它。`
- 实测：`frontend/src/App.tsx:609` 是 `if (agents.length <= 1) return null`。§1.3 恰恰要求评委先建全新库 → 只有默认 `finance-assistant` → 选择器不渲染。走查中创建第二个 Agent（`finance-reader`）后，选择器才出现，标签为「由谁处理」，选项为「默认 · 财税助手」与「只读查询员 · v1 · 只读」。
- 改法：补前置条件，例如「**创建第二个 Agent 后**，首页输入框下方才会出现『由谁处理』选择器；只有默认 Agent 时不显示，任务自动绑定默认 Agent」。

**3. 第 82 行（§3）表单字段清单错两处**

- 现文：`填写名称、职责、简介、提示词、"模型配置"（及可选的模型名）、勾选"能力"（可用工具）、"技能"和"适用企业"`。
- 实测表单字段顺序为：**Agent 编号**、名称、职责、提示词（追加在系统安全规则之后，不能覆盖它们）、模型配置、模型名（可选，覆盖该配置的模型）、能力（7 个工具复选）、技能、适用企业。
- 两处错误：**没有「简介」这个字段**；**必填的「Agent 编号」文档完全没提**（占位符 `例如 finance-reader`，不填则提交按钮禁用）。
- 改法：`填写 Agent 编号（必填，例如 finance-reader）、名称、职责、提示词、"模型配置"…`，删掉「简介」。

**4. ~~第 142 行（§7 表格）「上下文压缩」这一栏在界面里不存在~~ — 本条已撤回，原判断错误**

- **更正（2026-11-19 23:20，Acceptance Agent）**：原判断**错误**。`frontend/src/App.tsx:546-556` 有 `Compression` 组件，渲染 `<section aria-label="上下文压缩"><h3>上下文压缩</h3>`，位置在检查器内「任务计划 / 待办」之后。浏览器实测（真实模型、两轮对话）该区域正常渲染，文本为：「上下文压缩 — 最近几轮原文直接提供；更早的轮次只保留你的限制与更正和关键对象，原文可由 Agent 用 recall_context 取回。已对话 1 轮 · 折叠 0 轮 · 提及的编号：C001」，与原文档 §7 该行几乎逐字一致。
- 错误原因：走查时只跑了 1 轮就打开检查器。`Compression` 在 `contextSummary(turn.modelInput)` 为空时返回 `null`，而 `conversation` 字段**从第二轮起才存在**（`frontend/src/api.ts:240-254`、`runtime/ContextProjector.java:84-91`），首轮看不到属正常行为。我把「当时没看到」误判成了「不存在」，并据此错误地报告「全站无『压缩』二字」。
- **需要的动作：恢复 `作品使用说明.md` §7 表格的「上下文压缩」一行**（Coding Agent 依本条错误建议删掉了它，并把说明并入「本轮模型输入（记忆投影）」）。并入的 `conversation` 字段说明本身准确、可保留作补充，但不应取代该行。建议恢复为：`| 上下文压缩 | 已对话轮数、被折叠轮数、当前生效月份、早期轮次中你的限制与更正、提及的编号；原文可由 Agent 用 recall_context 取回。**从第二轮起才显示**，首轮没有 |`。补上「从第二轮起才显示」正是本次误判的根因，写明可避免评委同样困惑。
- 仍然成立的部分：T34「真实模型未复测」——本次两轮对话只触发了压缩区域的**渲染**，未覆盖大工具结果裁剪与 `recall_context` 取回原文的完整路径（该轮模型确实调用了 `recall_context`，一次 SUCCESS、一次 INVALID_ARGUMENT）。

**5. 第 120 行（§5.2）知识问答示例无法复现**

- 现文：`追问资料（"住宿费上限是多少？"会检索上传的制度并给出文件、版本、位置）`。
- 实测：交付材料里没有含住宿标准的制度文件；只上传官方 `02_示例数据.xlsx` 时，知识库仅有 R001/R002/R004，检索必然空手。
- 改法：换成可答的例子（例如「银行流水能不能证明某张发票已付款」→ 命中 R004 1.0），或在 §4 明确要求评委先上传一份合成制度文件并随包提供该文件。

**6. 第 121 行（§5.3）「该字段会显示为'待保存'」没说在哪显示**

- 实测：对话页**看不到**「待保存」。数据层完全正确：库中 `awaitingSave=['F004.purpose']`、`supplied` 已记录、`invoices` 中 F004 的 purpose 仍为空（确认未写入）。「待保存」只出现在 **检查器 → 上下文来源 → 任务进度**（形如「待保存：F004.purpose」）。
- 改法：`该字段会在「检查器 → 上下文来源 → 任务进度」中显示为"待保存"，任务保持开放…`

### 较小的

**7. 第 138–146 行（§7 表格）漏了两个实际存在的区域**

检查器里还有「**任务原始数据（可能包含敏感字段）**」和「**真实审计**」两节，表格未列。建议补两行；前者尤其要提醒评委含敏感字段。

**8. 时间显示是 UTC 但不标注（§3 第 82–86 行、§4 第 115 行附近）**

实测：本地时间 14:54 时，Agent 卡片显示 `2026-11-19 06:54`，文件列表显示 `07:00:12`。审计用 UTC 是既有设计，但配置页/文件页差 8 小时会让评委以为记录错了。改法：文档加一句「界面时间均为 UTC」，或界面补 `UTC` 后缀／改本地时区。

**9. 第 65 行（§1.5）建议补一句强制刷新**

服务端行为是对的（`/` 返回 302 → `/react-app/`，带 `Cache-Control: no-store, no-cache, must-revalidate`）。但走查用的浏览器 profile 里缓存着早期版本的静态页，访问 `/` 时先渲染了旧界面。评委若复用装过旧演示的浏览器可能撞上。改法：加「若看到的是旧版页面，请强制刷新（Cmd+Shift+R）」。

### 走查中确认与文档一致的部分（无需改动，供撰写时引用）

- §1.3 种子基线逐项吻合：2 企业 / 2 用户 / 4 权限 / 40 发票 / 48 流水 / 5 规则；C001 九月缺用途为 F002、F003、F004、F006、F009。
- §1.5 健康检查返回 `{"status":"UP"}`；`/` 302 跳转 `/react-app/`。
- §2 身份对话框（角色/企业下拉、U002 提示）与表格一致；viewer+C002 → `PERMISSION_DENIED` 且**会话未被切换**（fail-closed）；viewer+C001 可浏览文件列表，`input[type=file]` 与「上传并解析」均 disabled，并显示「只读成员可以查看资料，不能上传」。
- §3 新建 Agent 生成 v1，卡片含能力/实际可用/企业/模型/版本历史；能力中心工具版本（`check_invoice_purpose` 2.0、`update_invoice` 2.0、其余 1.0）、使用者、启停、技能区、知识区齐全；规则 CLI **照抄文档命令 exit 0**，输出 R001 1.0 / R002 2.0 / R004 1.0。
- §4 上传官方工作簿：accept 类型、10 MB、归属 C001；结果「没有新增发票或流水；44 条与已有记录同编号，未覆盖；44 条属于其他企业，未导入；共 104 个可引用文本段」，冲突逐行给出「工作表 · 行号 · 编号」；查看内容按「说明!第N行」等原文位置分段，显示 SHA-256 前缀；检索中/停用开关可用。
- §5.1 建任务带待补字段、标题显示绑定 Agent 与版本、首轮真实调用 2 个只读工具。
- §5.3 补充只记录声明、不写发票（见上第 6 条的库内证据）。
- §6 全链路通过：审核页只列本轮查询到的 10 张发票 → 二次确认弹窗含 `operationId` 与「未知结果不自动重试」→ `verified=true · 数据库回读已核验` → 库内 F002 purpose 已写入、`invoice_operations` 恰好 1 条且带 `task_id`、C001 九月缺用途 5 → 4、审计 `verified=1 fields=purpose` 且**无用途明文**、无残留操作锁。
- §7 检查器其余区域齐全（任务计划/待办、执行计划六步、上下文来源五个分区、本轮模型输入、完整模型输入、历史工具摘要）；「实际执行活动 · N 个工具调用」可展开看参数与结果；审计页脱敏正确（有 `update_invoice` 与核验标记，无金额/用途明文）。
- §8 错误码：`UNSUPPORTED_SCOPE` 与 `PERMISSION_DENIED` 均以自然语言呈现，并如实交代「本轮没有修改任何发票」。

> 处理状态（2026-11-19，Coding Agent）：上述 9 条已在 `作品使用说明.md` 修正（第 1 条同时改了 `App.tsx` 输入框占位符；第 4 条采用方案 a，未新增 UI；第 8 条以文档注明 UTC，未改界面），明细见 `TASKS.md` T37 末条。尚待浏览器复核。

> **上注已过期（2026-11-20，Acceptance Agent）**：第 4 条的判断本身是错的，「方案 a」已被推翻——§7 的「上下文压缩」一行已恢复并补上「从第二轮起才显示」。浏览器复核亦已完成。详见 `TASKS.md` T37 的 2026-11-19 23:25 验收条与 2026-11-20 的收尾条。

## 13. 2026-11-20 交接状态（复赛 T28–T37 全部 DONE，已推送）

**交接点**：`main` = `origin/main` = `97e4689`，注解 tag `v0.2.0` 指向同一提交并已推送；工作树除官方材料与 `审计docs/` 外干净；8080 端口空闲；无残留 worktree。本次推送经用户明确指示（AGENTS.md 默认禁止推送），为快进推送、未使用 force；`v0.2.0` 在推送前从未发布过，故移动 tag 是安全的。

**复赛范围状态**：T28–T37 全部 DONE。验收证据与「哪些路径经真实模型验证、哪些只有离线测试」已逐条写进 `TASKS.md` 各任务状态行，请勿在后续编辑中把两类证据混为一谈。

### 仍缺的真实模型验证（不是缺陷，是覆盖缺口）

- **T34**：压缩区域的**渲染**已真实验证（连续两轮，检查器可见）；但**大工具结果裁剪**与 `recall_context` 取回原文的完整路径未经真实模型验证。该轮模型调用 `recall_context` 一次 SUCCESS、一次 INVALID_ARGUMENT，后者的成因未追查。
- **T33**：长期记忆的跨轮注入未经真实模型验证（走查中只打开了对话框、核对了三个范围与字段，未实际保存偏好并验证下一轮模型输入里出现它）。
- **T36**：故障注入路径（模型超时、连续错误熔断、响应丢失、进程重启）仍只有离线测试覆盖。
- 若要补，最省事的是在真实模型下跑一次 30 轮含更正的对话（T34 的原验收标准），一次即可同时覆盖压缩裁剪、`recall_context` 与记忆注入。

### 新发现：初赛部分有 8 处状态行从未收尾（提交包观感问题）

`TASKS.md` 中以下条目仍标 `IN_PROGRESS` 或**根本没有状态行**，但其描述的工作在当前树里已可观察到完成：

| 行 | 条目 | 观察 |
|---|---|---|
| 230 | T11 — 回归与故障注入用例（计划阶段） | 无状态行 |
| 236 | T11 — 回归与故障注入验证记录 | 无状态行 |
| 270 | T13 — 打包提交说明及复现文档 | 无状态行 |
| 284 | T13 — DashScope Messages 适配（进行中） | 标题仍写「进行中」 |
| 314 | T15 — 独立 React + TypeScript 产品前端 | `IN_PROGRESS`，但 React 前端就是当前唯一 UI，已随 v0.2.0 交付 |
| 323 | T15 — 提交前两个安全阻断修复 | `IN_PROGRESS`，但其下「两项安全修复最终验收（2026-10-31）」已单独记录完成 |
| 335 | T15 — 损坏操作锁存储 fail-closed 补修 | `IN_PROGRESS`，条目内自己写着「本次补修验收：DONE（T15 整体仍 IN_PROGRESS）」 |
| 341 | T16 — 初赛评审对齐收尾 | `IN_PROGRESS`，但其「已完成」清单里的旧静态页删除已核实（`src/main/resources/static/` 不存在，JAR 内只有 `static/react-app/`） |

**建议动作**：逐条对照各自的验收标准后改为 DONE，或加一行「初赛阶段收尾未回填状态，工作已随 9045c5c/v0.2.0 交付」。**验收 Agent 未代为修改**——判断某条是否真达标属于开发侧职责，且初赛范围已冻结（`docs/BUSINESS_CONTRACT.md`），不宜由验收方单方面改写台账。

另注：这些旧条目里仍写着「禁止 Git add/commit/push」等初赛期的串行约定，与后续经用户授权提交、推送的实际做法已不一致；收尾时宜一并说明其适用期间，避免评审误读为规则被违反。

> 处理状态（2026-11-20，Coding Agent；仅回填台账，未改代码）：8 处已逐条对照各自验收标准收尾。T13 DashScope 适配、T15 三条、T16 → **DONE**（缺口均已由 T17/T18/T20/T37 补齐，状态行写明去向）；T11 计划条 → **SUPERSEDED**（T11 验证记录本已有 DONE 状态行，上表「无状态行」不准确）；T13 打包复现文档 → **CLOSED，未全部达标**：空缓存首次在线构建与真正全新 checkout 从未验证，仍作为已知限制保留在 `REPRODUCE.md`，没有记成 DONE。总表 T13 行同步；`TASKS.md`「工作记录」开头加了旧「禁止提交/推送」约定的适用期间注记。

## 14. 2026-11-20 T38 交付后补强（DONE，未提交/未推送）

- 依据用户「还有什么不足之处，继续完善」开出，细节见 `TASKS.md` T38。用户在 Mac 三轮验证：后端 335 项、前端 `npm run verify` 通过；真实模型 30 轮长对话（`scripts/long_conversation_check.py`，证据 `docs/evidence/T38_LONG_CONVERSATION_20261120/`）程序行为项两轮全部通过。
- 行为变化（接手时注意）：
  - 只读工具的 `INVALID_ARGUMENT`/`NOT_FOUND` 在同一工具随后成功时解除，拒绝、停用、存储、超时错误从不解除。
  - `recall_context` 遇到未知 `call_id` 时，会列出本任务最近可取回的调用。
  - `PurposeCheck` schema 写明只在问到用途时调用。
  - `UNSUPPORTED_SCOPE` 失败回复附固定原因说明。
  - 界面时间改为本地时区显示。
  - **有待补/待保存字段时，模型 `Finish` 的原文保留，后接“任务尚未完成。”与待补提示**（用户 2026-11-20 决定，取代 T16/T35 的整段替换），`task.question` 只存待补提示。
- 尚未实测：call_id 提示与用途检查说明对真实模型的效果（长对话脚本未在最终代码上重跑）；大工具结果裁剪分页仍只有离线测试。

## 15. 2026-11-20 T39（DONE，随本次提交入库）

- 修掉一个真实缺陷：`recall_context` 取回的一页本身可能超过 6000 字符，会被 `AgentRuntime.compactForModel` 二次压缩成 3000 字符预览，模型以为拿到整页。现由 `ContextRecall.MAX_PAGE_CHARS`（5000）逐行装入直到超限，备注写明本页行数与继续用的 `offset`；非行结果截断上限 `MAX_JSON` 由 12000 降到 4500。
- 真实模型验证：后端 `mvn test` **336 项 0 失败**；`scripts/large_result_check.py`（qwen3.7-flash、全新库、60 张合成发票）**9 项全通过**，证据 `docs/evidence/T39_LARGE_RESULT_20261120/`。**第 14 节末条「大工具结果裁剪分页仍只有离线测试」已被本次实验推翻**，该路径现为真实模型验证。
- 全新检出验证：提交 `0c98ec4` 后跑 `scripts/fresh_checkout_check.py`，**13 步全部通过**（约 92 秒）——新临时目录 `clone --no-local`、空 Maven 仓库在线 `test` 336/0/0/0、在线 `package`（实际下载 Node v22.12.0 并 `npm ci`，JAR 内含 `static/react-app/index.html`）、新库初始化、loopback `/api/health`=UP 与 `/react-app/` 200、离线 `-o package` 复跑；证据 `docs/evidence/T39_FRESH_CHECKOUT_20261120/`。`REPRODUCE.md` 中「空缓存首次在线依赖下载、真正全新 Git checkout 未验证」的旧结论已就地加更正。
- 仍只有离线测试的：T36 故障注入（模型超时、连续错误熔断、响应丢失、进程重启）。仍未验证：其他平台、Maven 最低版本、真实视频、最终压缩包。
- 本机 8080 / 18084 无残留监听；本次提交前 `main` 已含 `0c98ec4`（T39 代码），本条为其验收记录。

## 16. 2026-11-20 T40（DONE，已随 `cf30578` 推送）

- 新增 `scripts/fault_injection_check.py`：真实 JAR、全新库、loopback，模型换成本机桩服务（Anthropic Messages 协议），不需要密钥。8 个场景：超时、429、畸形回复两次/一次、工具错误熔断、保存提交后 SIGKILL（响应丢失）、批准轮写入前 SIGKILL、流式中途断开。
- 用户 Mac 运行一次 **40 项全通过**，证据 `docs/evidence/T40_FAULT_INJECTION_20261120/`。**第 15 节末条「仍只有离线测试的：T36 故障注入」已被本次运行推翻**；用的是桩模型，证明的是程序处理，不是真实模型行为。
- 未改业务代码与测试；后端仍 336 项。
- 仍未验证（写于 T40 时）：其他平台、Maven 最低版本、真实视频、最终压缩包。**其中「其他平台」「Maven 最低版本」已由 T41 实测关闭，见第 17 节**；剩真实视频、最终压缩包。

## 17. 2026-11-20 T41（DONE，已推送）

- 缺陷：`mvnw` 在 Git 中是 100644，全新克隆后 `./mvnw` 报 Permission denied。**注意**：本节初稿写「工作树已改为 100755」并不成立——21:06 复核时索引与磁盘都还是 644，那次 chmod 没生效。已用 `chmod +x mvnw && git add mvnw` 落到索引并单独提交 `892e217`（`mode change 100644 => 100755`）。
- `fresh_checkout_check.py` 新增 `--maven <版本>`（下载并核对 SHA-512）与 `--maven wrapper`。版本识别会跳过 JDK 24+ 的警告行并去掉颜色码；每次运行前清理旧日志。（曾在 Windows 下把临时目录展开成长名，见下；该规避已由 T42 删除。）
- **Maven 3.6.3 + JDK 21 已实测 15/15 通过**，证据 `docs/evidence/T41_MAVEN_363_20261120/`。
- **Maven Wrapper + JDK 21 已实测 15/15 通过**（`892e217`），证据 `docs/evidence/T41_WRAPPER_20261120/`。
- **GitHub Actions 四作业全绿**（run `44748507398`，提交 `9ff7830`）：Ubuntu/Windows/macOS 用 Wrapper、Ubuntu 另用 3.6.3；证据 `docs/evidence/T41_CI_20261120/`。
- 前两次 CI 只在 Windows 失败，暴露两个真问题，均已修：
  - `67b7a0e`：`AgentRuntimeTest` 的超时用例断言通过后，被放弃的工具线程仍占着 `runtime.db`，JUnit 删 `@TempDir` 在 Windows 上失败。测试改为等文件可删除再结束。
  - `9ff7830`：runner 的 `%TEMP%` 是 8.3 短名（`RUNNER~1`），Node 按长名解析，Vite 因此产出绝对 chunk 名、`npm run build` 失败。检查脚本当时改用 `GetLongPathNameW` 展开的目录克隆；**此规避已被 T42 的产品侧修复取代并删除**，见第 18 节。
- 仍未验证：真实视频、最终压缩包。CI 当时残留的 Node 20 提示已由 T43 升级 action 版本处理，见第 19 节。

## 18. 2026-11-20 T42（DONE，已推送）

- 产品侧修掉 T41 只在检查脚本里规避的 Windows 短路径问题：`frontend/vite.config.ts` 的 `root` 取 `realpathSync.native(new URL('.', import.meta.url))`。**必须用 `.native`**——`fs.realpathSync` 是 JS 实现，在 Windows 上不展开 8.3 短名；第一版修复因此无效（`b4b9bd6`），改用 native 后 CI run `44755130890` 四作业全绿。
- 新增 devDependency `@types/node@^22`（`node:fs` 类型）；`package.json` 仅加一行，lock 仅加 `@types/node`、`undici-types` 两条。
- `scripts/fresh_checkout_check.py` 的 `long_path` 规避已删除，Windows CI 现在直接跑产品配置（克隆到短名 `%TEMP%` 路径）。
- 构建时会打印一行 `[vite] root … (cwd …)`，用于在 CI 日志里区分短名与展开后的长名，长期保留作诊断线索。（T43 起只在 root 与 cwd 不同时打印，见第 19 节。）
- 证据 `docs/evidence/T42_VITE_SHORT_PATH_20261120/`（Windows 14/14 摘要 + 那行 root 日志）。


## 19. 2026-11-20 T43 收尾清理（DONE，已推送，`220b26c`）

- 本文第 16、17 节的过时说法已更正（T40 实际已推送；「其他平台/Maven 最低版本」已由 T41 关闭；检查脚本的长名规避已被 T42 删除）。
- `frontend/vite.config.ts`：`[vite] root … (cwd …)` 只在展开后的 root 与 cwd 字符串不同时打印。正常本地构建、`npm run dev`、Vitest 不再输出；Windows 短名路径（以及 macOS `/var` → `/private/var` 这类符号链接）下照常输出，CI 诊断能力不变。
- `.github/workflows/fresh-checkout.yml`：`actions/checkout@v4→v5`、`actions/setup-python@v5→v6`、`actions/upload-artifact@v4→v6`，均已核对各自 `action.yml` 为 `using: node24`（`upload-artifact@v5` 仍是 node20，故直接取 v6）。输入参数未变。
- 已验证：本地 `npm run verify` 通过（54 项测试），构建不再打印 `[vite] root`；CI run `44767147980` 四作业全绿，注释区只剩 macOS 容量提示（Node 20 弃用提示已消失），Windows 日志仍有 `[vite] root C:\Users\runneradmin\… (cwd …RUNNER~1…)`。证据 `docs/evidence/T43_CI_20261120/`。
