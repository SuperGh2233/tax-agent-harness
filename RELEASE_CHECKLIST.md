# 比赛交付检查单

T13 文档及缓存条件下独立复现已准备；**尚非已完成比赛提交**（缺演示视频与最终作品包）。截至 2026-11-11：JDK21 Java 全量 **252** 项、前端 Vitest 37 项通过；真实模型 `qwen3.7-flash`（DashScope Anthropic 兼容入口）的 API 三场景（查询+R004、受控保存、跨轮补充保存）与真实 Chrome 浏览器内的查询、受控保存、跨轮补充、`verified=true` 回读、脱敏审计均已通过；规则变更即时生效、`agent_trace` 运行记录持久化已实测。剩余未完成：≤5 分钟演示视频、最终作品包、空缓存首次在线构建验证、跨 JVM 并发验证。本轮改动（规则正文解析、运行记录持久化、日期序列化修复、`Cache-Control`、模型口径纠偏）**尚未提交/推送**。下列改动与证据不代表比赛最终作品包或报名提交已完成；不会打包或推送数据库、密钥或录屏。演示与录制按 [DEMO_SCRIPT.md](DEMO_SCRIPT.md)（含强制干净库与数字口径步骤）执行。队友接手先读 [开发交接说明](docs/agent-handoff.md)，完整命令见 [REPRODUCE.md](REPRODUCE.md)。

## 官方要求对照

依据：冻结 [BUSINESS_CONTRACT.md](docs/BUSINESS_CONTRACT.md)“写入、工具和三场景约束”“评分、提交和范围边界”；本次只读复核官方 v1.1.0《01_参赛说明》“基础要求”“提交内容”。下表表示材料/实现证据，不代表官方验收或得分。

| 官方要求 / 出处 | 本仓库证据 | 当前边界 / 用户待办 |
|---|---|---|
| 源码、依赖与启动说明（提交内容） | pom.xml、src、REPRODUCE；JDK21 全量 **252** 项、前端 37 项、JAR 构建通过 | 本轮改动尚未提交/推送；空缓存首次在线及新 checkout 待验证 |
| 简要设计建议2–3页（提交内容） | `docs/参赛作品设计说明_2-3页.docx` 已成稿（含已完成/未完成、模型与程序分工、记忆与知识管理、一个设计选择） | 提交前通读复核页数与口径；不改官方计划原件 |
| 关键运行记录、查看模型输入/记忆/规则/工具与结果（基础6、提交内容） | `docs/evidence/` 三份记录 + 22 张截图；完整 promptTrace、UI 工具时间线、脱敏审计、`agent_trace` 持久化 | 测试 Fake 证据需标注来源；用户审核去敏后抽取附件，不复制整个 target |
| ≤5分钟演示（提交内容） | `DEMO_SCRIPT.md` 已按实测重写：强制新建库+基线核对、先保存后补充六段、单字段待补、操作锁清理、数字口径表 | **无视频**；按脚本预演后录制，回看时长与敏感信息 |
| 查询、补充保存、多轮检查；追问、更正、跨会话、规则选择、真实保存数据（任务范围/提交内容） | 真实 `qwen3.7-flash` 下 API 三场景 + 真实 Chrome 浏览器查询、受控保存、跨轮补充、`verified=true` 回读、脱敏审计全部通过 | 数字口径须标明库状态（新建库 5 张 / 保存 F002 后 4 张）；补充后同任务再查询会结项，演示顺序按脚本执行 |
| 模型理解决策，程序计算/校验/保存（基础1、5） | Runtime/FinanceAgent、整数分、权限/授权/白名单/幂等/回读测试 | 无关键词生产替代；MODEL_NOT_CONFIGURED不冒充成功 |
| 任务及明确偏好查看更正删除、用户企业隔离（基础2） | TaskMemory服务与测试、UI任务恢复 | 长期偏好管理在服务层，非全量UI管理；真实长对话待验证 |
| 规则导入查找更新停用及名称版本（基础3） | `RuleService` 按存储正文解析「每张必填 / ≥X 元必填」两表述；`RuleAdminCli --rules-import/--rules-disable/--rules-list`；实测停用 2.0 导入 3.0 后缺口 5→1 张并引用 `R002@3.0` | 无图形管理后台（赛题不要求）；矛盾或未知措辞仍 fail-closed；管理入口仅本地可信终端 |
| 当前数据与原Excel分开（材料阅读顺序） | 新独立SQLite初始化及当前数据工具 | 只打包官方模拟原件/种子，不打包运行DB |
| 不交真实密钥、个人数据、虚拟环境、大体积依赖（提交内容） | .gitignore、清单审查、隔离缓存命令 | 忽略不能阻止已跟踪文件泄露；用户审阅最终压缩包每个条目 |
| 时间/入口/大小以主办方通知为准（提交内容） | 契约待核实项 | 本地无法确认，用户自行核实；内部11月11日不是官方截止 |

评分权重仅引用官方：基础任务40%、AI应用25%、问题理解15%、完善空间10%、创新10%。自动测试通过不换算为分数。

## 交付前顺序

1. 审查 `git status --short`、`git diff --name-only`、`git ls-files`；保留T12未提交工作，禁止清理/覆盖。由用户后续按仓库权限完成代码整合，本任务不提交。
2. 在完整干净源码副本按REPRODUCE逐条验证：JDK21、首次在线test/package、后续离线、种子核对、新DB初始化、127.0.0.1启动/健康/UI、停止。记录版本、日期、测试计数（当前基线 252 项）、每条结果。当前只证实既有隔离缓存条件，T13保持IN_PROGRESS。
3. 用户整理2–3页设计说明：已完成/未完成、模型程序分工、记忆知识管理、一个设计选择（精确授权+幂等+回读）及限制/扩展路径。不得冒充官方计划原件。
4. 用户审阅关键运行记录，注明Fake/stub或真实供应商来源；只用官方模拟数据，移除密钥、个人信息和本地敏感路径。运行DB留本机，不进入交付。
5. 用户依DEMO_SCRIPT在全新独立库预演、手动录≤5分钟视频，展示真实工具及保存回读；无模型时只能展示安全状态，不能剪成成功演示。用户手动回看、检查时长、音画和敏感信息。视频放仓库外、单独加入最终作品附件。
6. 手工准备全新作品暂存目录，按下面白名单复制，检查每个条目后由用户压缩与提交。不要直接压缩工作目录或使用当前HEAD archive（会漏T12）。本任务不生成最终ZIP、不操作提交入口。
7. 赛前门禁（必须人工确认）：`target/t10-evidence/scorecard.json` 中 `unauthorizedWrites` 和 `successWithoutVerification` 均为 0；审计中没有“未核验却提示保存成功”的事件；真实浏览器只读/写入 E2E、演示视频和去敏运行记录已人工复核。任一项不满足不得声称比赛提交完成。

## 文件白名单与禁止项

源码材料：pom.xml、mvnw、mvnw.cmd、.mvn/**、AGENTS.md、TASKS.md、README.md、REPRODUCE.md、RELEASE_CHECKLIST.md、DEMO_SCRIPT.md、.gitignore、无密钥.env.example、src/**、scripts/official_seed.py、scripts/secret_scan.py、scripts/dependency_scan.ps1、scripts/osv_scan.py、scripts/osv_suppressions.json、scripts/package_offline.ps1、scripts/setup_local_model.ps1、docs/**、官方v1.1.0三份原件与既有项目计划书；React 工作台材料：frontend/package.json、frontend/package-lock.json、frontend/index.html、frontend/tsconfig.json、frontend/vite.config.ts、frontend/eslint.config.js、frontend/README.md、frontend/src/**、frontend/tests/**。React 构建由 `mvn package` 在 prepare-package 自动完成并复制到 JAR 的 `static/react-app/**`；不提交 `src/main/resources/static/react-app/**`，不要带入 `frontend/dist`、`frontend/node_modules` 或 `target/**`。只复制已人工核对的明确文件，src/docs/frontend 内如有额外临时文件也要剔除。用户另备设计说明、去敏关键运行记录及手动视频附件；不默认这些已经存在。

禁止：.git、.env及变体（仅.env.example例外）、data/**及任意运行数据库/sidecar、.m2/**、target/**、虚拟环境、IDE缓存、临时财务CSV/XLSX/JSON、未经审阅日志/截图/录屏、真实密钥或证书。官方02_示例数据.xlsx和版本化seed JSON是明确允许的模拟原件/种子；不得一概用*.xlsx忽略误删官方数据。任何新财务导出即使不匹配忽略规则也不得加入白名单。

```sh
git status --short
git diff --check
git ls-files -ci --exclude-standard
# 预期最后一条无输出（没有已跟踪但被忽略的产物）；不是秘密扫描证明。
git check-ignore data/harness.db .m2/repository/example target/example .env tmp/finance.csv recordings/demo.mov exports/finance.xlsx
```

本次安全补充.gitignore：tmp/、recordings/、exports/，SQLite扩展及journal、常见视频、pem/key。原有data/.m2/target/.env及db规则保留。忽略规则只是兜底，不保证任意文件名的秘密/临时数据会被识别；最终包仍按白名单逐项审阅，不把真实密钥内容打印到日志。

## T13 Git变更清单

| 文件 | T13变更 |
|---|---|
| REPRODUCE.md（新增） | 独立副本、在线/离线命令、新库、安全启动、排障和证据边界 |
| RELEASE_CHECKLIST.md（新增） | 本检查单、官方要求对照与人工交付步骤 |
| docs/plans/active/PLAN-20261030-t13-release-reproduction.md（新增） | 目标/范围/验证/风险及实际结果 |
| README.md（已有T12修改上追加） | 显眼交付入口和安全简要步骤；保留历史内容 |
| docs/index.md（已有T12修改上追加） | T13文档索引 |
| TASKS.md（已有T12修改上修改T13行并追加） | 认领IN_PROGRESS、准确证据、限制及下一步 |
| .gitignore | 安全追加临时财务导出目录、数据库旁文件、密钥与视频模式 |

原有T12：application.yml、demo Java包、static资源、DemoApiTest、DEMO_SCRIPT、T12计划与API契约，以及README/TASKS/index原有内容均保留；不属于本次业务变更。没有业务代码修改、commit或push。
