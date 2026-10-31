# Tax Agent Harness — 共享开发任务池

> 项目：财税 Agent Harness（参赛队伍：用豆包就够了！）
>
> 状态：`TODO` 待认领 · `IN_PROGRESS` 开发中 · `BLOCKED` 阻塞 · `DONE` 验收通过。
>
> 这是开发执行台账；完整业务背景和设计以仓库中的《财税_Agent_Harness_项目计划书_V1.1_共享任务池.docx》以及官方 v1.1.0 材料为依据。如有冲突，以官方材料和双方确认后的决策为准。

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
| T13 | P1 | 打包提交说明及复现文档 | T11、T12 | IN_PROGRESS | Coding Agent | 干净源码+既有缓存187/0/0/0、JAR/新库/HTTP/UI通过；空缓存在线及全新checkout未证实，见T13记录 |

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
- Git 当前 main 无上游跟踪分支，且插件状态有未展开的受限变更，**不要在未确认前清理或盲目提交**。

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
