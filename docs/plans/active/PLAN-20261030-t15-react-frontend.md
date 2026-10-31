# T15 — 独立 React 产品前端

状态：active / IN_PROGRESS；认领人：Coding Agent；2026-10-30。

## 目标与范围
Vite + React 19 + TypeScript、Tailwind、Radix 基础组件。深海军蓝/石墨工作空间与 Minimal 信息密度，首页、执行对话、审核/结果三页。范围已按用户授权包含 frontend、既有 DemoController 的受保护 cancel 路由及 FinanceAgent 入口、静态首页安全修复、回归和指定文档；未新增 Controller，不改数据库、冻结契约或官方材料。docs/index 保持不变。

## 契约与交互
以 DemoController、TaskMemory、AgentRuntime、ToolContract、DemoAudit 记录定义 typed adapter。GET/POST/DELETE session；tasks list/start/load/supplement/turn；audit。same-origin/no-store、session csrfToken，每次切换刷新 token。非流式请求只显示等待与真实 history。恢复不恢复授权。模型建议不是授权；手填精确变更、逐字段核对、二次确认绑定一次性 operationId；未知结果禁止自动重试，保留操作编号。verified 只来自 TurnView.verification 数据库回读，不从模型文本推导。

## 实施
1. 保存既有文件 SHA256 基线并认领。
2. 核对依赖官方文档；小型自有 REST Runtime Adapter + Thread/Composer/ToolGroup：当前接口无流式/AG-UI 事件，避免额外运行时桥接。借鉴 assistant-ui 组件职责和 CopilotKit 人工介入模式，不引入其运行时。
3. 实现可键盘操作导航、默认关闭的 Radix 检查器/移动 Drawer，真实审计仅按需请求，字段回读与明确确认。
4. typecheck、lint、build，隔离合成 SQLite 后端 smoke、浏览器响应/键盘/二次确认检查、diff 与原文件哈希复核。

## 部署与回退
默认 build 输出 frontend/dist、base=/react-app/，不将构建产物写入 static；本轮仅修改既有 static/demo.js 的未知写入锁，保留默认首页。README 提供 Spring static 外部目录配置与可选发布复制方案。取消使用既有 DemoController 的受保护路由，未新增 Controller。dev proxy 将 API Origin 改为目标 loopback，避免既有 Origin 校验冲突，不改后端。回退只移除本任务新增资产和追加记录，不动既有文件。

## 验收与风险
npm typecheck/lint/build；首页、身份、任务列表、start/load、turn 缺模型态、audit、二次确认均验证；不对现有库写入。CSP、嵌套路径与 /react-app/ Spring 映射需实证。金额整数分不做浮点财务运算，原始工具数据默认折叠。真实模型/实际财务写入不在验收内。未验证项明确保留 IN_PROGRESS。

## 验证证据与当前进展（更新至 2026-10-31）
- React 19 / Vite / TypeScript / Tailwind 4 / Radix 工程、首页/任务工作区/任务历史/审计/审核流程、typed REST Adapter、工具调用分组和审批 UI 已落到 `frontend/`。用户在 Mac 本机执行 `npm run verify`，提供终端输出确认 TypeScript、ESLint、Vitest 4/4、Vite 7.3.7 生产构建全部通过（1636 modules；JS 278.44 KB，gzip 88.72 KB）。
- 安全复核发现此前 App 可能把“工具历史未出现 update_invoice”错误当作未写入证据。已改为仅当 `operationId` 匹配且 `verification.verified===true` 才解除未知写入锁；网络异常提示保留操作号。新增覆盖超时空历史、未核验、错误操作号、成功回读的单测，用户执行的 4/4 验证包含这些用例。
- 依据用户提供的工作区截图，发现已结束历史任务恢复后只有一条提示，Composer 又因 COMPLETED/CANCELLED 被禁用，形成“提示重新查询但无法继续”的交互矛盾。已新增终态识别 `isTerminalTaskStatus`；历史终态任务展示专用历史面板和“基于原任务新建查询”，只预填原目标，实际创建时调用 `POST /tasks` 生成独立任务；不继承旧任务写入授权、查询证据或审批。新建任务会清理历史状态。已区分“恢复的历史终态任务”和“本轮刚结束的任务”：前者不伪装成实时对话，后者保留本轮真实消息，并显示任务结束提示及新建入口。审核入口在无本轮响应/历史终态时禁用。
- 新增终态分类测试：COMPLETED/CANCELLED 为终态，ACTIVE/WAITING_USER 可继续。用户报告已执行新一轮验证，但本次未收到其终端输出，不能声称该轮测试通过。
- 新任务发布路径代码审查定位两个确定问题：首页原来只执行 `POST /tasks`，没有立即调用首轮 `/turn`；创建完成后的 `GET /tasks` 刷新若失败，会将已创建成功的任务误报为整体失败。已新增 `createAndRunFirstTurn`，先创建并立即展示新任务 ID 与原始输入，再以同一目标调用一次无审批的首轮 `/turn`。模型失败保留已建任务与错误状态，不自动重试；列表刷新失败保留可见任务并单独告知；创建响应未知时引导先查任务历史，避免重复创建。新增三项测试：新任务首轮恰好执行一次/无 approval、首轮异常仍保留新任务、创建失败不执行 turn。原 5 项 + 新 3 项，预期 8 项，**还未跑最新 npm verify**。
- 从用户截图看到新建任务 `CONFLICT`。根因已在实际源码确认：`task-memory.sql` 使用 `one_open_agent_task` 唯一索引，同一用户/企业同时只允许一个 `ACTIVE` 或 `WAITING_USER` 任务；之前创建界面没有表达此约束，也没提供关闭旧任务的能力。前端已实现明确提示、继续旧任务、刷新状态、结束旧任务确认对话框、已存在未完成任务时禁用新建；后端突发 409 会同步拉取未完成任务并展示操作入口，不自动再次创建。为打通流程，**本次新增最小 Java 受保护路由** `POST /api/demo/tasks/{id}/cancel`，要求 confirmed=true、任务版本、CSRF 和可信会话，使用已有 `TaskMemory.cancel` 乐观锁及用户企业作用域，并记录审计；业务发票数据与历史任务不会被删除/回滚。前端有待核验 operationId 时禁止取消。增加 Java 2 项 HTTP/权限回归和 Vitest 3 项取消/冲突状态测试，均需复验。
- 此项修复因用户的新需求合理扩大 T15 范围到 `DemoController.java`、`FinanceAgent.java` 和 `DemoApiTest.java`，未改财税规则、原始数据、原版 HTML/JS、密钥、SQLite 库、官方资料。仍待用户在 Mac 验证：`frontend/npm run verify`、JDK21 `mvn test` 与后端重新打包重启（Vite 热更新不会更新 Java 服务），最后在**独立模拟库**核验 ACTIVE/WAITING_USER 取消后能新建，及权限/CSRF/审计。`/react-app/` Spring 静态映射、CSP、真实模型联调尚未完成；**未暂存、未提交、未推送，T15 继续 IN_PROGRESS**。


## 提交前安全修复计划（2026-10-31）
- 进程内固定64个条带锁，按可信 userId+enterprise 选择；turn、cancel、supplement 共用并覆盖状态读取、执行与审计，保留数据库作用域/CAS/CSRF。仅单 JVM loopback demo，不提供跨 JVM 或强杀超时 worker 保证。
- 静态页发送批准请求前仅保存 {id,taskId,unknown:true}，与 React 共用 taxagent-pending-operation；只在同 operationId 且 verification.verified===true 时清锁，异常/未核验保持锁。重载恢复警告，只读操作可用，不自动重试。
- 验收：两个独立会话用 CountDownLatch 控制模型执行，与取消/补充并发，验证任务终态和一次写入；静态脚本隔离执行回归验证未知结果、重载和匹配回读。离线 Java21 test/package、npm verify、node --check、diff --check。禁止 clean；必要时仅 touch 修改的 Java。回退仅撤销本节修复，不清理原有产物。
- 当前：本轮两项修复DONE；T15其他未验收范围继续IN_PROGRESS，最终证据如下。

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
