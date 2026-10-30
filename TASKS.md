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
| T12 | P1 | 简易交互界面、审计日志与演示录制 | T10 | TODO | — | 工具执行日志可查看；演示真实写入验证 |
| T13 | P1 | 打包提交说明及复现文档 | T11、T12 | TODO | — | 干净环境按 README 可启动、初始化、测试、演示 |

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
