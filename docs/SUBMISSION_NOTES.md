# 初赛提交说明（草稿）

> 截止：**2026-11-13**。本文件是操作清单，不是“已完成提交”的声明。视频、官方模型复测和最终 zip 未完成前不得勾选完成。

## 1. 提交内容

- 源码与启动说明：仓库源码、`README.md`、`REPRODUCE.md`、`mvnw`/`mvnw.cmd`。
- 可执行 JAR：`target/harness-0.0.1-SNAPSHOT.jar`（由 `mvn clean package` 生成，内含 React 构建）。
- 2–3 页设计说明：`docs/参赛作品设计说明_2-3页.docx`。
- 去敏运行记录：`docs/evidence/REAL_E2E_20261106.md` 及浏览器截图。
- ≤5 分钟演示视频（待录制）。
- 不提交：`.git`、`.env*`、真实密钥、`data/**`、临时数据库、`frontend/dist`、`frontend/node_modules`、`target/**`、录屏源文件。

## 2. 模型与接口配置

官方材料（`01_参赛说明.docx`、`03_业务规则.docx`）**未指定具体模型名**，只要求使用赛事允许的模型，并通过配置项切换模型地址、模型名和鉴权信息。因此本作品不绑定任何供应商或型号。

**本项目统一使用 `qwen3.7-flash`**（DashScope Anthropic 兼容入口）；全部真实 E2E、演示截图与视频均基于该模型，**不使用 Qwen3.8-Max**。本机安全终端配置（不要写入仓库）：

```powershell
$env:LLM_ENDPOINT = "https://dashscope.aliyuncs.com/apps/anthropic"
$env:LLM_MODEL = "qwen3.7-flash"
$env:LLM_PROTOCOL = "anthropic"
$secure = Read-Host -AsSecureString "LLM_API_KEY"
$env:LLM_API_KEY = [System.Net.NetworkCredential]::new("", $secure).Password
```

若官方提供 OpenAI-compatible 网关，改 endpoint 并设 `LLM_PROTOCOL=openai-chat`；客户端会自动追加 `/chat/completions`，支持批量 tool_use 和自然语言总结。

## 3. 构建与启动

正常联网构建：

```powershell
.\mvnw.cmd -B -ntp -DskipTests clean package
```

首次构建会下载 Node/npm 并执行 `npm ci`。离线评委环境：

1. 联网机器先完成一次构建，保留 `frontend/dist`；
2. 执行 `scripts/package_offline.ps1`（内部 `mvn -o -Dfrontend.skip=true package`）；
3. 或直接使用已构建 JAR。不要在没有 `frontend/dist` 时跳过前端，否则 JAR 没有 UI。

独立临时库启动：

```powershell
$demoDir = Join-Path $env:TEMP ("taxagent-demo-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $demoDir | Out-Null
$db = Join-Path $demoDir "demo.db"
java -Djava.io.tmpdir=$demoDir -Dorg.sqlite.tmpdir=$demoDir -jar target\harness-0.0.1-SNAPSHOT.jar --initialize-data "--database=$db"
java -Djava.io.tmpdir=$demoDir -Dorg.sqlite.tmpdir=$demoDir -jar target\harness-0.0.1-SNAPSHOT.jar "--taxagent.database=$db" --server.address=127.0.0.1 --server.port=18084
```

浏览器打开 `http://127.0.0.1:18084/`，固定跳转 `/react-app/`。

## 4. 视频建议脚本（≤5 分钟）

1. **0:00–0:30**：说明赛题二、团队、合成数据/loopback，强调模型、规则、程序工具职责边界。
2. **0:30–1:40 查询**：Viewer/C001 查询 2026-09 发票、银行收支、用途缺口；展示 R002/R004 规则版本、四个工具时间线和执行计划。
3. **1:40–2:50 受控保存**：Operator/C001 补充 F002 用途，明确确认保存；展示 `verified=true`、operationId 台账、数据库回读、剩余缺用途 4 张。
4. **2:50–4:00 跨轮/恢复**：重新加载任务，说明历史不是当前证据；补充后只读核验/保存，展示证据门。
5. **4:00–4:40 上下文与审计**：检查器四栏（任务进度、长期偏好、规则、企业上下文）、完整 prompt trace、脱敏审计。
6. **4:40–5:00**：边界与创新总结：一次性授权、幂等、写后回读、失败不美化、可扩展模型配置。

真实保存后必须回读；失败/超时镜头不要剪成成功。

## 5. 提交前门禁

- [ ] `qwen3.7-flash` 三场景真实复测并记录（本作品统一模型，不做其他型号复测）。
- [ ] React 浏览器内查询、受控保存、跨轮补充保存均通过。
- [ ] 视频 ≤5 分钟，真实模型、真实数据保存与回读。
- [ ] `REAL_E2E` 记录和截图已去敏，无密钥/个人数据/真实财务数据。
- [ ] Java 252/0/0/0、前端 37/37、`mvn clean package`、OSV 0 未抑制。
- [ ] `scripts/secret_scan.py` + 人工审阅最终 zip 每个条目。
- [ ] 设计说明 2–3 页、TASKS/REPRODUCE/RELEASE_CHECKLIST 与最终包一致。
- [ ] 最终 zip 命名和团队信息按官网要求确认。

## 6. 当前状态（2026-11-11 更新）

- 已完成：React 单一前端、执行计划/上下四栏、自然错误提示、完整 prompt trace、审计保留策略、OSV 扫描、离线构建脚本、TASKS 记录、规则变更即时生效、`agent_trace` 持久化与 `/traces` 回读。
- 已验证：`qwen3.7-flash` 三场景真实 E2E（查询 → 待补追问 → 跨轮续接 → 受控保存 → 回读 → 审计）、React 浏览器保存与跨轮、252/37 测试、clean package、OSV 0 未抑制。
- 未完成：≤5 分钟演示视频、最终 zip、设计说明 docx 按最新结论校对。
- 模型口径：本项目统一 `qwen3.7-flash`，**不使用 Qwen3.8-Max**；官方未指定型号，配置项可切换，无需型号复测。
- Git：本轮 P1/P2 改动已提交/推送到 `origin/work/zhanghengyuan1`；`5b19cdd` 是此前的提交；2026-11-11 的规则/持久化修复改动尚在工作树，待人工确认后提交。