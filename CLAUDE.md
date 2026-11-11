# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

本文件补充命令与大图架构。开发流程、保护边界以 `AGENTS.md` 为准，二者冲突时先读 `AGENTS.md`、`TASKS.md` 与 `docs/BUSINESS_CONTRACT.md`。

## 项目定位与硬约束

- 比赛/研究型本地演示项目，不是生产财务系统；仅面向 loopback，单 JVM，无生产认证，不得暴露公网。
- 只处理官方合成的 2 家企业（C001/C002）、2 个用户（U001/U002）、40 张发票、48 条流水、5 条规则版本。禁止使用真实财务数据。
- 官方材料的业务边界冻结在 `docs/BUSINESS_CONTRACT.md`；不要修改官方材料、计划书、冻结契约或“已 DONE”任务的范围。
- 唯一可变业务字段是发票 `purpose` 与 `remarks`。银行记录只读；不得新增、删除、修改。用途缺口检查只支持 2026-09。
- 金额由程序用整数分（`amount_cents`）计算，发票合计与银行收入/支出独立统计，不抵销、不匹配、不推断付款。
- 密钥只通过环境变量注入，绝不写入源码、日志、文档、截图或 Git。`.env` 不会自动加载。
- 不擅自 `git add`/`commit`/`push`，不覆盖他人工作树。开工前先读 `TASKS.md`、`git status`，认领一个无冲突任务；完成并实际验证后才标 DONE。

## 常用命令

环境：JDK 21（本机默认 `java` 可能是 25，必须显式切换）、Maven 3.6.3+、Node 22.12+。

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
export REPO_CACHE="$PWD/.m2/repository"
```

### 后端

```sh
mvn test                                      # 全量测试；不触发前端构建
mvn -Dtest=FinanceAgentIntegrationTest test   # 单个测试类
mvn -Dtest=FinanceAgentIntegrationTest#scenarioOneMonthlyQueries test
mvn -DskipTests package                       # 构建 JAR（会构建前端，首次需联网）
mvn -DskipTests -Dfrontend.skip=true package  # 仅后端打包（需已有 frontend/dist 才能出完整 UI）
mvn spring-boot:run
```

仓库默认用隔离缓存：`-Dmaven.repo.local="$REPO_CACHE"`。首次联网成功后可用 `-o` 离线复跑。`mvn package` 在 `prepare-package` 阶段安装 Node/npm、`npm ci`、`npm run build`，并把 `frontend/dist` 复制到 JAR 的 `static/react-app/`。

### 初始化与启动

初始化参数是 `--database`，服务参数是 `--taxagent.database`，不能混用：

```sh
DEMO_DIR="$(mktemp -d)"
DEMO_DB="$DEMO_DIR/demo.db"
java -jar target/harness-0.0.1-SNAPSHOT.jar --initialize-data --database="$DEMO_DB"

env -u LLM_ENDPOINT -u LLM_API_KEY -u LLM_MODEL \
  java -jar target/harness-0.0.1-SNAPSHOT.jar \
  --taxagent.database="$DEMO_DB" --server.address=127.0.0.1 --server.port=8080

curl http://127.0.0.1:8080/api/health   # {"status":"UP"}
```

`--initialize-data` 导入种子后直接退出，不启动服务；重复初始化只补缺失键，从不覆盖已有记录。每次演示都用全新临时库，否则缺用途张数等数字会随历史写入漂移。

### 模型配置

```sh
LLM_ENDPOINT=https://dashscope.aliyuncs.com/apps/anthropic
LLM_API_KEY=...
LLM_MODEL=qwen3.7-flash
LLM_PROTOCOL=anthropic
```

- `DemoConfiguration` 在 `LLM_PROTOCOL=anthropic` 或 endpoint 路径含 `/anthropic` 时选 `AnthropicMessagesClient`（追加 `/v1/messages`）；否则选 `OpenAiCompatibleClient`（`/responses` 或追加 `/chat/completions`）。不自动回退。
- 缺少任一配置时返回 `MODEL_NOT_CONFIGURED`，不会用假模型伪造成功。模型超时 60 秒；重试次数为 0。
- 换模型只改环境变量，不要把型号写进代码或规则。

### 前端

```sh
cd frontend
npm ci
npm run dev        # http://127.0.0.1:5173/react-app/ ，/api 代理到 127.0.0.1:8080
npm run verify     # typecheck + lint + Vitest + build
npx vitest run tests/api.test.ts   # 单个前端测试文件
```

后端端口不同时用 `T15_BACKEND_ORIGIN=http://127.0.0.1:<port> npm run dev`。Vite 只监听 loopback，不引入 CORS。

### 规则与偏好 CLI（不启动 Web）

```sh
java -jar target/harness-0.0.1-SNAPSHOT.jar --taxagent.database="$DEMO_DB" \
  --rules-list --enterprise=C001 --date=2026-09-15
# --rules-import / --rules-update（更新=新增版本，原版本不可改写）
# --rules-disable --rule-id=R005 --version=1.0
# --prefs-list / --prefs-put / --prefs-delete
```

## 架构大图

主链：`DemoController → FinanceAgent → TaskMemory / AgentRuntime → LlmClient → 财税工具 → SQLite`。

- **`DemoController`**（`demo/`）：本地演示 REST API。每个请求新建 `FinanceAgent`；状态都在 SQLite。`DemoPrincipal` 只由固定 profile 映射构造（operator→U001，viewer→U002），绝不由 HTTP 请求体或模型参数反序列化。
- **`DemoSecurity`**：只允许直接 loopback、同源 Origin、无转发头、GET/POST/DELETE、CSRF Token、请求体 ≤64KB、每 IP 每分钟 600 次。所有信任来自可信会话，不来自 `userId` 参数。
- **`FinanceAgent`**：任务/记忆/运行时/写入的组合入口。`approvedTurn` 是唯一写入通道，必须由可信 UI 传入本轮精确 `operationId` + `InvoiceUpdate` + 明确 `Intent`。
- **`TaskMemory`**：SQLite 持久化任务、待补字段、用户补充、长期偏好、工具摘要、服务端 plan 和 agent trace。状态 `ACTIVE / WAITING_USER / COMPLETED / CANCELLED`；乐观 `version`；数据库唯一索引保证同一用户/企业最多一个未结任务。`supplement` 只接受用户明确陈述，绝不从模型文本提取。
- **`ContextProjector`**：每轮把任务上下文裁剪为 ≤4000 字符的 JSON 再交给模型；完整上下文仍以数据库为准。投影不是证据、不是写入授权。
- **`AgentRuntime`**：模型决策循环（最多 8 步）。决策可为单工具、最多 8 个只读工具的批量、`ask`、`finish`。整批先预校验再串行执行；含 `update_invoice` 的批次直接拒绝。模型把结束协议发成 `finish`/`ask` 伪工具调用时按文本协议归一化。
- **工具层**（`tool/`）：`query_invoices`、`query_bank_entries`、`check_invoice_purpose`、`query_rules`、`update_invoice`。`ToolContract` 用严格 JSON 反序列化（拒绝未知字段、标量强制转换、重复键），身份与授权不在工具参数里。
- **`PermissionGuard`**：每次业务操作在事务内重新查 `permissions` 表；自然语言不能改变身份或权限。
- **`InvoiceWriteService`**：一次性 `Authorization` 对象（消费后不可复用）、字段白名单、`operationId` 台账幂等、事务内回读、`verified=true` 才算成功。超时/未知结果只能走只读 `recover`/`verify`，空结果不等于可以重试。
- **证据门**：模型文字、`FINISHED`、旧轮历史都不是保存成功的证据。批准轮没有本轮 `update_invoice` 的 `verified=true` 回读时，只能返回 `WRITE_NOT_EXECUTED` 或 `WRITE_NOT_VERIFIED`，任务保持 ACTIVE。
- **`DemoAudit`**：append-only 脱敏审计；每次变更前先写 REQUESTED，审计不可用则 fail closed。`agent_trace` 记录每轮模型输入摘要。
- **`RuleService`**：只执行已存储规则文本中的既有表述（`每张必填` / `≥X 元必填`），按企业、日期、启用状态选版本；矛盾或无法解析时返回 `UNSUPPORTED_SCOPE`，不猜。规则版本不可原地修改，只新增版本/停用。
- **模型适配**（`runtime/`）：`LlmClient` 是供应商无关的决策协议；`AnthropicMessagesClient` 负责 Messages 批量 tool_use 的原始消息回放；`OpenAiCompatibleClient` 同时处理 Chat Completions 与 Responses。系统提示强调工具事实、规则引用、诚实失败。
- **SQLite**：纯 JDBC，无 JPA。种子在 `src/main/resources/seed/`；`task-memory.sql` 与 `invoice-operations.sql` 在服务构造时做可加迁移；服务连接使用 `mode=rw`，要求数据库已初始化。
- **前端**：React 19 + Vite，唯一 UI。`api.ts` 中信封字段为 camelCase，工具结果 JSON 为 snake_case。审核只能选取本轮实际 `query_invoices` 的发票；逐票逐字段二次确认后生成一个 `operationId`。写入锁在 `localStorage`/`sessionStorage` 中 fail-closed，只有精确匹配且 `verified=true` 才释放；不自动重试。SSE 流式只影响展示，最终以末帧 `done` 为准。

## 测试约定

- Java：JUnit 5 + AssertJ + `@TempDir`。集成测试先 `DataInitializer.initialize(db)` 建独立官方种子库，再用脚本化 `FakeLlmClient` 驱动真实工具执行；不发真实网络请求。
- `DemoApiTest` 用 standalone MockMvc + `DemoSecurity` 覆盖会话、CSRF、权限、并发与负例。
- 前端：Vitest，测试在 `frontend/tests/`。
- 真实模型/浏览器验证只能使用合成数据、独立临时 SQLite、loopback，并且要与自动化测试分开记录；API 通过不等于 UI 验证通过。

## 容易踩的坑

- `mvn test` 不会构建前端；正常 `mvn package` 才会。离线环境先在有网络的机器构建出 `frontend/dist` 或预构建 JAR。
- 演示顺序有约束：在“等待补充”状态下直接审核保存；补充清空待补字段后再查询会让任务结项，写入按钮随即禁用。详情见 `DEMO_SCRIPT.md` 与 `docs/evidence/`。
- 工具结果中的日期必须是 ISO 字符串，不能回退成时间戳数组（曾导致前端审核页拿不到发票）。
- 同一用户/企业已有 ACTIVE/WAITING_USER 任务时再次创建会 `CONFLICT`；这是数据库约束，不是 bug。
- 写入待核验锁会跨会话/换库残留，演示前清理 `taxagent-pending-operation*` 存储键。
- 不要用 `data/harness.db` 或已有库做初始化/演示；`data/`、`*.db`、`.env`、`target/`、`.m2/` 都不入库。
- 规则“更新”是新增版本，不是原地改写；停用后检查必须随当前启用版本变化。
