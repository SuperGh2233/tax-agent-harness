# 比赛交付检查单

T13 文档及缓存条件下独立复现已准备；**尚非已完成比赛提交**。不执行 Git commit/push，不自动打包数据库、密钥或录屏。完整命令见 [REPRODUCE.md](REPRODUCE.md)，手动演示见 [DEMO_SCRIPT.md](DEMO_SCRIPT.md)。

## 官方要求对照

依据：冻结 [BUSINESS_CONTRACT.md](docs/BUSINESS_CONTRACT.md)“写入、工具和三场景约束”“评分、提交和范围边界”；本次只读复核官方 v1.1.0《01_参赛说明》“基础要求”“提交内容”。下表表示材料/实现证据，不代表官方验收或得分。

| 官方要求 / 出处 | 本仓库证据 | 当前边界 / 用户待办 |
|---|---|---|
| 源码、依赖与启动说明（提交内容） | pom.xml、src、REPRODUCE；JDK21测试187项及JAR构建通过 | 未提交T12必须包含；空缓存首次在线及新checkout待验证 |
| 简要设计建议2–3页（提交内容） | README技术路线、现有项目计划书、工具及存储契约可供整理 | 现有长计划书不等同2–3页成稿；用户另写附件，不改原件 |
| 关键运行记录、查看模型输入/记忆/规则/工具与结果（基础6、提交内容） | FinanceAgentIntegrationTest生成target/t10-evidence；UI工具时间线、脱敏审计 | 测试Fake证据需标注；用户审核去敏后抽取附件，不复制整个target |
| ≤5分钟演示（提交内容） | DEMO_SCRIPT含时序和macOS录屏步骤 | 无视频；用户配置并实测真实模型后手动录制、回看时长和敏感信息 |
| 查询、补充保存、多轮检查；追问、更正、跨会话、规则选择、真实保存数据（任务范围/提交内容） | T10三场景、T11故障回归、T12 UI和隔离测试 | Fake+真实临时SQLite通过；未实测真实云模型三场景，不声称已完成云演示 |
| 模型理解决策，程序计算/校验/保存（基础1、5） | Runtime/FinanceAgent、整数分、权限/授权/白名单/幂等/回读测试 | 无关键词生产替代；MODEL_NOT_CONFIGURED不冒充成功 |
| 任务及明确偏好查看更正删除、用户企业隔离（基础2） | TaskMemory服务与测试、UI任务恢复 | 长期偏好管理在服务层，非全量UI管理；真实长对话待验证 |
| 规则导入查找更新停用及名称版本（基础3） | RuleService可信本地服务与测试、官方版本种子 | 无独立管理CLI/UI；管理上下文仅可信代码，普通用户默认拒绝；不宣称管理入口完整可演示 |
| 当前数据与原Excel分开（材料阅读顺序） | 新独立SQLite初始化及当前数据工具 | 只打包官方模拟原件/种子，不打包运行DB |
| 不交真实密钥、个人数据、虚拟环境、大体积依赖（提交内容） | .gitignore、清单审查、隔离缓存命令 | 忽略不能阻止已跟踪文件泄露；用户审阅最终压缩包每个条目 |
| 时间/入口/大小以主办方通知为准（提交内容） | 契约待核实项 | 本地无法确认，用户自行核实；内部11月11日不是官方截止 |

评分权重仅引用官方：基础任务40%、AI应用25%、问题理解15%、完善空间10%、创新10%。自动测试通过不换算为分数。

## 交付前顺序

1. 审查 `git status --short`、`git diff --name-only`、`git ls-files`；保留T12未提交工作，禁止清理/覆盖。由用户后续按仓库权限完成代码整合，本任务不提交。
2. 在完整干净源码副本按REPRODUCE逐条验证：JDK21、首次在线test/package、后续离线、种子核对、新DB初始化、127.0.0.1启动/健康/UI、停止。记录版本、日期、187项或实际新计数、每条结果。当前只证实既有隔离缓存条件，T13保持IN_PROGRESS。
3. 用户整理2–3页设计说明：已完成/未完成、模型程序分工、记忆知识管理、一个设计选择（精确授权+幂等+回读）及限制/扩展路径。不得冒充官方计划原件。
4. 用户审阅关键运行记录，注明Fake/stub或真实供应商来源；只用官方模拟数据，移除密钥、个人信息和本地敏感路径。运行DB留本机，不进入交付。
5. 用户依DEMO_SCRIPT在全新独立库预演、手动录≤5分钟视频，展示真实工具及保存回读；无模型时只能展示安全状态，不能剪成成功演示。用户手动回看、检查时长、音画和敏感信息。视频放仓库外、单独加入最终作品附件。
6. 手工准备全新作品暂存目录，按下面白名单复制，检查每个条目后由用户压缩与提交。不要直接压缩工作目录或使用当前HEAD archive（会漏T12）。本任务不生成最终ZIP、不操作提交入口。

## 文件白名单与禁止项

源码材料：pom.xml、AGENTS.md、TASKS.md、README.md、REPRODUCE.md、RELEASE_CHECKLIST.md、DEMO_SCRIPT.md、.gitignore、无密钥.env.example、src/**、scripts/official_seed.py、docs/**、官方v1.1.0三份原件与既有项目计划书。只复制已人工核对的明确文件，src/docs内如有额外临时文件也要剔除。用户另备设计说明、去敏关键运行记录及手动视频附件；不默认这些已经存在。

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
