# Tax Agent Harness

**Reliable Execution. Verifiable Results.**

面向财税业务多轮任务的 Agent Harness 比赛项目，强调任务持续推进、规则可追溯、工具执行受约束，以及写入结果可验证。

- 参赛队伍：用豆包就够了！
- 项目状态：**T11 回归与故障注入验收通过（JDK 21 全量 163 项通过）；T12/T13 待完成，验收状态见 TASKS.md**
- 主要场景：发票与银行收支独立查询；发票用途检查与明确授权后的安全写入；多轮任务记忆及恢复。

## 文档入口

- [共享任务池与验收记录](TASKS.md)：**开发前先读这里**。不固定人员分工；认领 → 开发 → 测试 → 标记完成并写证据。
- [V1.1 项目计划书](财税_Agent_Harness_项目计划书_V1.1_共享任务池.docx)：项目整体设计（Word）。
- [官方赛题二初赛材料包](赛题二：财税Harness初赛材料包-v1.1.0/)：参赛说明、示例数据、业务规则。

## 技术路线

- Java 21、Spring Boot、SQLite、JUnit 5；
- 模型驱动的 Tool Calling + 独立 Harness 状态管理；
- 业务数据与规则版本严格隔离；金额和权限由确定性代码处理；
- 写操作经过权限、授权、字段白名单、幂等与保存后回读验证。

## 当前进度

已建立启动类与 `GET /api/health`，返回 HTTP 200、JSON `{"status":"UP"}`，仅表示应用可响应。

已实现 **T03 显式种子初始化**：schema.sql、官方 v1.1.0 JSON 种子、SQLite 数据库 `data/harness.db`（Git 忽略）、重复初始化保留已有记录。初始 2 个企业、2 个用户、4 项用户企业权限、40 张发票、48 条流水、5 个规则版本。金额以整数分保存，初始规则正文及元数据入库。

已实现 **T04 工具契约基础**：[工具规范](docs/specs/TOOL_CONTRACT.md)、[模型输入 JSON Schema](src/main/resources/tool-contract.schema.json)、不可变 DTO、统一错误码、严格参数解码及读取 SQLite permissions 的 PermissionGuard。工具参数不接收 user_id，身份必须由可信会话独立传入；未知用户/企业默认拒绝，规则管理默认拒绝。Schema 可通过 ToolContract.schema() 检查，尚未接入工具执行入口。

已实现 **T05 只读查询服务**：`ReadOnlyFinanceTools` 默认读取实际运行库 `data/harness.db`，也可由可信服务端代码传入数据库 Path。`queryInvoices(session, query)` 返回记录及 `totalCents`；`queryBankEntries(session, query)` 返回记录及 `incomeCents`、`expenseCents`。查询以企业和月份起止日期绑定参数，保留企业复合键，日期为 LocalDate，金额为整数分；空结果返回空列表与 0。每次在同一只读事务用 PermissionGuard 复检权限，数据库缺失或失败为 STORAGE_ERROR，无权限为 PERMISSION_DENIED，无会话为 AUTHENTICATION_REQUIRED（均为 DomainException）。读取当前数据库，不从种子或 Excel 回答。

可信调用示例：`new ReadOnlyFinanceTools().queryInvoices(trustedSession, new ToolContract.Query("C001", "2026-09"))`。`trustedSession` 必须来自服务端可信身份上下文，不能从模型或 HTTP 参数构造。当前无认证链路，因此仅提供服务层，无财务查询 HTTP API；银行只读，发票与流水独立统计。

已实现 **T06 RuleService**（仅本地 service 层）：`queryRules(session, enterprise, date)` 返回 ID、版本、名称、生效日、状态及完整出处正文；date=null 查看全部版本，指定日期仅取该企业已生效且启用的版本。`checkInvoicePurpose(session, new PurposeCheck(enterprise, "2026-09"))` 在同一只读事务检查权限、规则与当前 DB 发票，返回必补复合键和实际规则引用。甲企业每张空用途必补，乙企业金额 >=100000 分且用途空白必补；备注不替代用途，8 月明确 UNSUPPORTED_SCOPE。

这是官方已知版本的确定性执行器，不是通用 RAG：仅执行 R002 2.0 / R003 1.0 且名称、生效日、状态、完整正文与已核对官方定义一致的规则；当前是否启用及企业关联仍从 DB 读取，种子只用于核验可执行语义。缺失、停用、未知版本/改写正文返回 UNSUPPORTED_SCOPE，多个相关启用版本同时适用返回 CONFLICT，不猜测版本优先级。

规则管理仅由可信本地代码显式创建 `RuleService.trustedLocalManagement()` 上下文，调用 `importVersion(context,key,definition,enterpriseSet)` / `disableVersion(context,key)`；普通 Session 的 `manageRules` 一律拒写，包括 U001。该上下文不是官方管理员角色，不能接收 LLM/HTTP 自称管理员或反序列化工具参数；当前没有生产入口签发上下文。导入以事务保存版本及所有企业关联，拒绝重复版本、未知企业、空正文和无效状态；更新通过导入新版本完成，旧版本正文不覆盖。停用以版本为单位影响所有关联企业，保留全部版本记录。

已实现 **T07 当前请求 Agent Runtime**：`runtime.LlmClient` 返回 `Call` / `Ask` / `Finish`，由模型选择下一步，无关键词生产路由。`AgentRuntime` 将三个只读工具的冻结 Schema 提供给模型，严格解码参数，再调用 T05/T06 服务，权限在服务只读事务中复检。每个工具结果以 `{data,error}` JSON 连同调用 ID 回传模型；`Result.history` 可供可信调用方审查当前请求。工具出错后即使模型声称成功，结果仍为 ERROR 且不展示该成功文本。未调用工具的自然语言结束文本仍是模型生成内容，不构成业务执行证据。

默认最多 8 次模型决策，每次模型调用/工具执行 15 秒、无重试；`Limits` 可配置 1–32 步、至多 60 秒、模型至多重试 1 次，以及三个只读工具的子集。工具永不重试，不开放 update_invoice/rules/task/memory 或任何写入/管理能力。超时返回 MODEL_TIMEOUT/TOOL_TIMEOUT，达到步数为 MAX_STEPS，模型/协议故障为 MODEL_FAILURE，无配置为 MODEL_NOT_CONFIGURED；DomainException 保留既有错误码。失败不返回零金额或空查询成功。替换客户端应响应线程中断；超时会取消任务并立即结束请求，不能强杀不响应中断的第三方实现。

### 可选真实模型接入（未实测）

`OpenAiCompatibleClient` 使用 JDK HttpClient，无新增依赖，无自动启动调用。由可信服务端代码显式注入完整 chat/completions endpoint、API key、model 和超时；可从环境读取 `LLM_ENDPOINT`、`LLM_API_KEY`、`LLM_MODEL`。这些变量不是 Spring 自动装配项；未建立认证 HTTP 入口。示例仅说明装配方式，不会随应用启动执行：

```java
var client = new OpenAiCompatibleClient(
    URI.create(System.getenv("LLM_ENDPOINT")), // 完整 HTTPS .../v1/chat/completions
    System.getenv("LLM_API_KEY"), System.getenv("LLM_MODEL"), Duration.ofSeconds(15));
var runtime = new AgentRuntime(client, new ReadOnlyFinanceTools(),
    new RuleService(), AgentRuntime.Limits.defaults());
var result = runtime.run(trustedSession, userMessage);
```

endpoint 仅允许 HTTPS（本机 stub 可用 HTTP），不跟随重定向；缺 key/model 时 `next` 返回 MODEL_NOT_CONFIGURED，零网络请求。供应商须支持 function tools、`parallel_tool_calls:false`、单个完整 tool call；无工具时按 system prompt 返回严格 `{kind:"ask"|"finish",text:"..."}` JSON。每步至多一个调用，拒绝截断和多调用响应；不支持流式输出。真实接入会发送用户消息及已授权读取的工具结果，应仅由可信调用方显式启用，密钥不写入仓库或日志。

`AgentRuntime.run` 保持原有当前请求行为。**T08 TaskMemory** 提供可选持久化闭环：由可信后端传入已初始化数据库 Path 和 Session，`start` 建任务，`tasks/load` 查看，`supplement` 接收明确补充及更正，`continueTask` 加载上下文并交由 Runtime 模型决策，`cancel` 取消；`preferences/putPreference/deletePreference` 管理用户明确提出的长期偏好。完整调用与状态契约见 [T08 存储契约](docs/specs/SPEC-20261030-task-memory.md)。

```java
var memory = new TaskMemory(Path.of("data/harness.db"));
var task = memory.start(trustedSession, "C001", "查询九月发票后继续处理", List.of("purpose"));
var turn = memory.continueTask(trustedSession, "C001", task.id(), userMessage, runtime);
// Ask 后 WAITING_USER；下轮或服务重建后重新 load，使用任务最新 version 更正/取消。
// 以下仅演示已核对的用户明确补充，不能用模型推测调用：
var corrected = memory.supplement(trustedSession, "C001", task.id(), turn.task().version(),
    Map.of("purpose", new TaskMemory.Input("办公", "这张发票用途是办公")));
```

记忆独立存入 SQLite agent_tasks/agent_preferences；构造器只做幂等建表，不初始化或覆盖业务数据。所有访问以用户+企业隔离并每次复检当前权限，部分唯一索引限制一个未完成任务；取消/完成释放槽位，乐观版本冲突须重新加载。可跨对话、重建 service 恢复 WAITING_USER。工具摘要只保存工具名与成功/错误码，当前金额、用途及规则始终重新查询。模型追问保留为历史文本，不自动写入补充事实或偏好；可信后端负责核对用户明确原话，任务记忆不提供财务修改授权。工具参数不能伪造身份或跨任务企业执行。

Session 从外部可信代码独立传入；无生产登录链路，不开放业务或记忆 HTTP API。此处为 T08 历史验收；后续 T09/T10 结果见下文。真实模型供应商兼容性仍未实测；本次使用 Fake 模型及真实 SQLite。2026-10-30 09:39:48 +08:00，真实 Java 21.0.12.1 下执行 `JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test`：**127 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**（原有 116 + T08 11）。报告位于 target/surefire-reports，完整验收见 TASKS.md。

## 本地启动与测试

要求 JDK 21、Maven 3.6.3+；固定 Spring Boot 3.5.16、SQLite JDBC 3.53.4.0，JUnit 5 由 Spring Boot 管理。首次构建需要访问 Maven Central。

```sh
java -version
mvn -version
mvn test
mvn spring-boot:run
# 另一个终端
curl -i http://127.0.0.1:8080/api/health
```

默认监听 127.0.0.1:8080，Ctrl+C 停止；可通过 `SERVER_ADDRESS`、`SERVER_PORT` 覆盖。`.env.example` 无密钥；Spring Boot 不自动加载 `.env`，需要时可在当前终端执行 `set -a; . ./.env.example; set +a`。

本机默认 Java 25.0.4.1、Maven 3.10.0，另已安装 JDK 21.0.12.1。macOS 可在当前终端选择 Java 21，使用仓库内隔离缓存，不修改全局配置：

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test
mvn -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" spring-boot:run
```

集成测试启动完整应用，在随机端口通过真实 HTTP 检查状态码、JSON 类型与精确内容。健康接口不验证数据库或业务就绪。

## 官方数据初始化

普通启动**不会**重置数据库。需要初次创建或显式补齐缺失的官方记录时，执行以下初始化入口：

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" -DskipTests package
java -jar target/*.jar --initialize-data
# 可指定位置：java -jar target/*.jar --initialize-data --database=data/custom.db
```

默认写入 `data/harness.db`。相同复合键不覆盖已存在值，不能把重新初始化当作恢复修改过的数据的方式。可用 `python3 -B scripts/official_seed.py --check` 验证版本化种子与官方原件一致；测试需在具有系统临时目录写权限的环境运行。

## 开发约定

1. 开始工作前检查 `TASKS.md` 和 Git 状态。
2. 领取一个依赖已满足、尚未被认领的任务；把状态改为 `IN_PROGRESS`。
3. 只实现本任务范围；运行可重复的验证。
4. 验收后更新 `TASKS.md`，记录实际结果和 commit；必要时补充接口文档。
5. 严格以官方 v1.1.0 业务规则为准，禁止编造业务记录或越权修改。

## T09 安全写入服务

`InvoiceWriteService(Path)` 仅供可信后端使用，未注册 HTTP 或 Agent 工具。可信 Session 独立传入；可信入口核对用户明确立即保存/清空要求后，通过 `authorizeExplicitRequest` 签发绑定用户、企业、票据、精确内容、operationId 的一次性对象，再调用 `execute`。说明用途不授权保存；模型不得调用授权签发方法。仅 purpose/remarks 可改，备注可空，低金额可主动补用途。当前仅支持 2026-09 已核对规则，其他月份拒绝。

指定既有数据库时仅增量创建操作台账；同事务复查权限、规则和目标，保存后回读核验，提交成功才 verified=true。同键同内容只回查，不重复 UPDATE；不同内容冲突。超时/未知结果先 `recover` 查询台账和实际值，缺记录也不得盲目重写；禁止自动重试。完整约定见 [工具契约 T09](docs/specs/TOOL_CONTRACT.md)。本任务只用临时测试库，未对运行库执行迁移或写入。

2026-10-30 09:45:33 +08:00，JDK21 离线 Maven 全量 **145 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**，含 T09 18 项。命令及验收证据见 TASKS.md。此为 T09 历史验收，T10 结果见下文。

## T10 三场景端到端（本地服务层）

`FinanceAgent(Path, trustedSession, selectedEnterprise, LlmClient)` 组合 Runtime、TaskMemory 和写服务，绑定可信身份与企业；无业务 HTTP API。调用者必须来自可信应用入口，不能把任意传入的 userId 当认证。完整接口见 [T10 服务契约](docs/specs/SPEC-20261030-finance-agent.md)。

```java
var agent = new FinanceAgent(isolatedDatabase, trustedSession, "C001", client);
var task = agent.start("检查九月用途并补齐", List.of("F002.purpose"));
var turn = agent.turn(task.id(), "检查2026-09缺用途，缺少内容请追问");
// 可信入口核对用户明确补充后：仅存信息，不保存发票。
agent.supplement(task.id(), turn.task().version(),
    Map.of("F002.purpose", new TaskMemory.Input("办公采购", "F002用途是办公采购")));
// 只有核对真实用户明确要求“现在保存 C001 F002 的以下内容”后，应用才调用：
var update = new ToolContract.InvoiceUpdate(new ToolContract.InvoiceKey("C001", "F002"),
    Map.of("purpose", "办公采购"));
var saved = agent.approvedTurn(task.id(), "现在保存，并重新查询核对", operationId,
    update, InvoiceWriteService.Intent.SAVE_NOW);
// 模型仍决定是否调用工具；以 saved.result().history() 的实际工具结果为证据。
// 重建同用户/企业 FinanceAgent 后用 tasks()/load(id) 恢复；授权不会跨轮保留。
```

生产路径无关键词决策。模型获得自包含工具 Schema，但不能签发 authorization、改变 Session 或伪造工具 verified。模型文本及 FINISHED 只表示生成说明/结束本轮，不证明保存；必须检查实际 update_invoice 结果的 verified 与后续查询证据。失败返回 ERROR，不采用模型成功声明。超时或未知结果先 `recover(operationId,update)` 查询，禁止自动重试；任务状态保存与财务写入不在同一事务，任务保存失败也要先回查实际操作。

真实 Java 21.0.12.1 全量验收：2026-10-30 09:54:26 +08:00，**158/0/0/0，BUILD SUCCESS**（原有145 + T10 13）。使用 FakeLlmClient 与临时官方种子 SQLite；**未测试真实云模型**。三场景 smoke 另跑9项，命令：

```sh
JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home \
  mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" \
  '-Dtest=FinanceAgentIntegrationTest#scenarioOneMonthlyQueries+scenarioTwoDirectSaveAndSameOperationNeverUpdatesTwice+scenarioThreeResumeSupplementSaveAndRequery' test
```

运行后查看 `target/t10-evidence/query-*.json`（两企业三月份查询）、`direct-save.json`（明确保存/回查）、`C001.json`、`C002.json`（检查→追问→补充→保存→缺口减少→恢复）。包含模型输入、所选任务、工具 Schema、调用及实际结果；目标目录已忽略，可重复生成。报告在 target/surefire-reports；smoke 会覆盖该测试类的全量报告。仅临时库被修改，data/harness.db 不受影响。

## T11 回归与故障注入

T11 已完成回归与故障注入，未修改生产代码。新增 5 个测试用例，覆盖两企业财务写入成功后 TaskMemory 保存失败的关键提交边界、规则缺失不回退、模型有限重试、工具错误不重试，以及 SQLite 锁导致的工具超时。关键故障场景验证：发票及操作台账只提交一次，任务状态不误报完成；重建服务后通过只读 recover 和新鲜查询确认实际保存值，不再次执行写入。

真实 Java 21.0.12.1 全量复验：2026-10-30 11:42:51 +08:00，**163 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**。完整命令、覆盖范围及限制见 [TASKS.md](TASKS.md)。测试使用 Fake 模型和临时 SQLite；**真实云模型尚未实测**。T12（交互界面/审计日志/演示）与 T13（打包及复现文档）仍待完成。
