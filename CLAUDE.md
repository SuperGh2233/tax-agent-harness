# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

本文件补充命令与大图架构。开发流程、保护边界以 `AGENTS.md` 为准，二者冲突时先读 `AGENTS.md`、`TASKS.md`、`docs/FINAL_ROUND_CONTRACT.md` 与 `docs/BUSINESS_CONTRACT.md`。跨会话接手时先读 `docs/agent-handoff.md`。这里只写现在必须遵守的规则；每条规则的来由、任务编号和修复经过见 `TASKS.md` 与 Git 历史。

## 项目定位与硬约束

- 比赛/研究型本地演示项目，不是生产财务系统；仅面向 loopback，单 JVM，无生产认证，不得暴露公网。
- 复赛范围以 `docs/FINAL_ROUND_CONTRACT.md`（2026-11-18 批准）为准；初赛契约 `docs/BUSINESS_CONTRACT.md` 保持冻结，作为历史依据和回归基线，二者冲突时以复赛契约为准。不要修改官方材料、计划书、两份契约或“已 DONE”任务的范围。
- 官方种子（2 家企业、2 个用户、40 张发票、48 条流水、5 条规则版本）是初始数据和回归基线；可经原入口上传导入新的**合成**数据。禁止真实财务数据和个人数据。上传不能新增用户/权限，也不覆盖同键已有记录。
- 唯一可变业务字段是发票 `purpose` 与 `remarks`。银行记录只读，不得修改或删除。新增发票/流水只有两条路：文件上传导入（复赛契约 A1/B2），或扫描件经会计逐项确认后导入（用户 2026-11-21 批准偏离契约「待确认」第 4 条）；两条路都只补缺失键。
- 用途缺口检查不限月份：每张发票按其日期当日唯一启用的用途规则判断，无适用规则则 `UNSUPPORTED_SCOPE`（复赛契约 A2）。
- 金额由程序用整数分（`amount_cents`）计算，发票合计与银行收入/支出独立统计，不抵销、不匹配、不推断付款。
- 密钥只通过环境变量注入，绝不写入源码、日志、文档、截图或 Git。`.env` 不会自动加载。
- 根目录的 `.zip`、`.docx`、`演示讲稿.md`、`DEMO_SCRIPT.md` 和两个“赛题二”目录是官方原件或提交用材料：不要编辑（两份讲稿只在用户明确要求时改），也不要打进构建。
- 不擅自 `git add`/`commit`/`push`，不覆盖他人工作树。开工前先读 `TASKS.md`、`git status`，认领一个无冲突任务；完成并实际验证后才标 DONE。

## 常用命令

环境：JDK 21（本机默认 `java` 可能是 25，必须显式切换）、Maven 3.6.3+、Node 22.12+。

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
export REPO_CACHE="$PWD/.m2/repository"
```

`/usr/libexec/java_home` 只在 macOS 上有；Linux/Windows 直接把 `JAVA_HOME` 指向 JDK 21。下文的 `mvn` 都可以换成仓库自带的 Maven Wrapper `./mvnw`（Windows 用 `mvnw.cmd`，固定 Maven 3.9.9），CI 默认用它。

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

- `DemoConfiguration` 在 `LLM_PROTOCOL=anthropic` 或 endpoint 路径含 `/anthropic` 时选 `AnthropicMessagesClient`（追加 `/v1/messages`）；否则选 `OpenAiCompatibleClient`（路径以 `/responses` 结尾走 Responses，否则追加 `/chat/completions`）。不自动回退。
- 缺少任一配置时返回 `MODEL_NOT_CONFIGURED`，不会用假模型伪造成功。模型超时 60 秒，运行时不重试模型；回复格式无法解析时纠正重试一次（`LlmClient.CORRECTION`），仍失败为 `MODEL_RESPONSE_INVALID`。HTTP 401/403、429、其他错误分别映射为 `MODEL_AUTH_FAILED`、`MODEL_RATE_LIMITED`、`MODEL_HTTP_ERROR`，都不重试。
- 换模型只改环境变量，不要把型号写进代码或规则。开发期用 `qwen3.7-flash`，最终比赛用 `qwen3.8-flash`（`qwen3.8` 这个名称接口返回 400）。
- 扫描件读图用独立配置 `LLM_VISION_ENDPOINT / _API_KEY / _MODEL / _PROTOCOL`（`VisionClient`，必须是能读图的模型；`qwen3.7-flash` 可以，可与 `LLM_*` 填相同值）。未配置时扫描件走手工录入草稿，不回退到、也不继承 `LLM_*`。
- 命名模型配置：`LLM_<NAME>_ENDPOINT / _API_KEY / _MODEL / _PROTOCOL`，Agent 只引用配置名（`default` 即上面的 `LLM_*`）；未配置的名称返回 `MODEL_NOT_CONFIGURED`，从不回退到其他配置。密钥永不入库。

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
# --rules-import / --rules-update（更新=新增版本，原版本不可改写；用途规则正文不可执行时拒绝导入，--allow-unexecutable 放行并警告）
# --rules-disable --rule-id=R005 --version=1.0
# --prefs-list / --prefs-put / --prefs-delete
```

## 架构大图

主链：`DemoController → FinanceAgent → TaskMemory / AgentRuntime → LlmClient → 财税工具 → SQLite`。代码在 `src/main/java/com/taxagent/harness/`：根包是启动和入口（`HarnessApplication`、`DataInitializer`），`demo/` 是 HTTP 和安全（含审计、规则 CLI），`runtime/` 是 Agent 循环、记忆和模型适配，`tool/` 是工具、SQLite 迁移和写入服务。

### 入口与安全

- **`DemoController`** 每个请求新建 `FinanceAgent`，状态都在 SQLite。`DemoPrincipal` 只由固定 profile 映射构造（operator→U001，viewer→U002），绝不从请求体或模型参数反序列化。
- **`DemoSecurity`** 只放行直接 loopback、同源、无转发头、带 CSRF Token 的 GET/POST/DELETE；请求体 ≤64KB（上传接口除外），每 IP 每分钟 600 次，CSP 只放开 `img-src data:`。
- **`PermissionGuard`** 每次业务操作在事务内重查 `permissions` 表。**`DemoAudit`** 只追加、脱敏，变更前先写 REQUESTED，审计不可用则 fail closed。

### Agent 运行

- **`FinanceAgent.approvedTurn`**（批量为 `approvedBatchTurn`）是唯一写入通道，必须由可信 UI 传入本轮精确 `operationId` + `InvoiceUpdate` + 明确 `Intent`。保存载荷由程序生成，模型只发 `update_invoice {}`。
- **`AgentRuntime`** 最多 8 步，每步是单工具、≤8 个只读工具的批量、`ask` 或 `finish`；无批准或含多个 `update_invoice` 的批次拒绝。只读工具超时或 `STORAGE_ERROR` 重试一次；`update_invoice` 永不重试，写入 `STORAGE_ERROR`/超时即停本轮，结果只能经 verify/recover 判定；连续 3 次工具错误熔断。未解决的工具错误使本轮失败（只读参数错误在同一工具随后成功时解除），失败回复由 `AgentRuntime.honest` 按本轮工具证据说明真实数据状态。
- **`AgentDirectory` / `CapabilityRegistry`**：Agent、工具状态、技能都只新增版本、不改旧版；每轮可用工具 = (Agent 工具 ∪ 启用技能的工具) ∩ 全局已启用工具，修改从下一轮生效。技能不能授予 `update_invoice`，没有它的 Agent 收不到写工具。Agent 提示词追加在固定安全规则之后，不能覆盖。改 Agent 需对其覆盖的每家企业有修改权，改全局工具/技能需对所有企业有修改权。默认 Agent 为 `finance-assistant`。

### 记忆与上下文

- **`TaskMemory`**：同一用户/企业最多一个未结任务（唯一索引），乐观 `version`。`supplement` 只接受用户明确陈述，绝不从模型文本提取。补充的发票字段进入 `awaitingSave`，本轮批准写入 `verified=true` 前任务不能结项；此时模型 `Finish` 转为 `NEEDS_INPUT`（回复保留模型原文，后接“任务尚未完成。”与待补提示）。
- **长期记忆**分三个范围：`USER_ENTERPRISE`、`PERSONAL`（不得含企业/发票/流水编号）、`ENTERPRISE`（仅有修改权者可改）；同名越具体越优先，模型只收到胜出者。删除或过期即擦除：`secure_delete`，并在同一事务内替换 `agent_trace` 中的历史值。
- **上下文**：`ContextProjector` 每轮投影 ≤6000 字符，投影不是证据，也不是写入授权。超长工具结果压缩为摘要（合计仍对全部记录计算），模型用 `recall_context` 只读取回当前任务/企业的原文，每次重新校验访问。

### 写入与数据

- **证据门**：模型文字、`FINISHED`、旧轮历史都不是保存成功的证据。批准轮没有本轮 `update_invoice` 的 `verified=true` 回读时，只能返回 `WRITE_NOT_EXECUTED` 或 `WRITE_NOT_VERIFIED`，任务保持 ACTIVE。
- **`InvoiceWriteService`**：一次性 `Authorization`、字段白名单、`operationId` 台账幂等、事务内回读。超时/未知结果只能走只读 `recover`/`verify`，空结果不等于可以重试。批量保存（2–20 张）单事务全成或全不改，**每张**都要回读匹配。
- **`ToolContract`** 严格反序列化（拒绝未知字段、标量强制转换、重复键）；身份与授权永不出现在工具参数里。
- **`RuleService`** 只执行规则正文里的 `每张必填` / `≥X 元必填`，按企业、日期、启用状态选版本；矛盾或无法解析返回 `UNSUPPORTED_SCOPE`，不猜。
- **`FileService`**：认不出的表头交会计做列对应后再导入；扫描件须经会计确认（金额输两遍、大写金额比对）才建发票。
- **SQLite**（纯 JDBC，种子在 `src/main/resources/seed/`）：按请求新建的服务建表/播种必须走 `tool/Migrations.once`，否则并行请求会 SQLITE_BUSY → HTTP 500；写连接一律经 `tool/Sqlite.open`（IMMEDIATE），只读事务走 `openForRead`（`mode=ro`）。时间戳以 UTC `Instant.toString()` 入库。

### 前端

- React 19 + Vite，唯一 UI；信封字段 camelCase，工具结果 snake_case。审核只能选本轮 `query_invoices` 返回的发票。写入锁 fail-closed，只有精确匹配且 `verified=true` 才释放，不自动重试。SSE 只影响展示，以末帧 `done` 为准。

## 测试约定

- Java：JUnit 5 + AssertJ + `@TempDir`。集成测试先 `DataInitializer.initialize(db)` 建独立官方种子库，再用脚本化 `FakeLlmClient` 驱动真实工具执行；不发真实网络请求。
- `DemoApiTest` 用 standalone MockMvc + `DemoSecurity` 覆盖会话、CSRF、权限、并发与负例。
- 前端：Vitest，测试在 `frontend/tests/`。
- 真实模型/浏览器验证只能使用合成数据、独立临时 SQLite、loopback，并且要与自动化测试分开记录；API 通过不等于 UI 验证通过。
- 进程级检查脚本在 `scripts/`，都跑在已打好的 JAR 上（合成数据、独立临时库、只走 loopback）：

  ```sh
  python3 scripts/fault_injection_check.py --jar target/harness-0.0.1-SNAPSHOT.jar   # 本机桩模型，无需密钥；--skip timeout 跳过超时场景
  python3 scripts/long_conversation_check.py --jar target/harness-0.0.1-SNAPSHOT.jar # 真实模型，消耗额度
  python3 scripts/large_result_check.py --jar target/harness-0.0.1-SNAPSHOT.jar      # 真实模型，消耗额度
  python3 scripts/fresh_checkout_check.py [--maven 3.6.3|wrapper]                     # 全新克隆＋空缓存构建，只覆盖已提交内容，需联网
  ```

- 发布前另用 `secret_scan.py`（打包前查密钥）、`dependency_scan.ps1` → `osv_scan.py`（依赖漏洞扫描，需联网）、`official_seed.py --check`（核对种子与官方 Excel）、`innovation_compare.py`（创新对比，真实模型）、`package_offline.ps1`（离线打包）；交付顺序与文件白名单见 `RELEASE_CHECKLIST.md`，各脚本用法见 `REPRODUCE.md`。
- CI（`.github/workflows/fresh-checkout.yml`）只跑 `fresh_checkout_check.py`：Ubuntu/Windows/macOS × Wrapper，外加 Ubuntu × Maven 3.6.3。CI 里没有密钥，也不调用模型；未提交的改动它看不到。

## 容易踩的坑

- `mvn test` 不会构建前端；正常 `mvn package` 才会。离线环境先在有网络的机器构建出 `frontend/dist` 或预构建 JAR。
- 工具结果中的日期必须是 ISO 字符串，不能回退成时间戳数组（前端审核页会拿不到发票）。
- 同一用户/企业已有 ACTIVE/WAITING_USER 任务时再次创建会 `CONFLICT`；这是数据库约束，不是 bug。
- 写入待核验锁会跨会话/换库残留，演示前清理 `taxagent-pending-operation*` 存储键。
- 不要用 `data/harness.db` 或已有库做初始化/演示；`data/`、`*.db`、`.env`、`target/`、`.m2/` 都不入库。
- 规则“更新”是新增版本，不是原地改写；停用后检查必须随当前启用版本变化。
- `frontend/vite.config.ts` 用 `realpathSync.native` 固定 Vite root，这是为了修 Windows 8.3 短路径（`RUNNER~1`）导致的构建失败。不要把它“简化”成 `process.cwd()`，也不要换成非 native 的 `realpathSync`。
