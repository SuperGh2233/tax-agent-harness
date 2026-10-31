# T12 本地界面、审计与演示准备

状态：completed / DONE；2026-10-30；认领人：Coding Agent。

## 目标与边界
原生 HTML/CSS/JS 本地浏览器入口连接 T10 FinanceAgent，支持固定模拟身份、数据库企业权限、任务发起/恢复/补充、模型对话、实际工具结果、精确写入确认和脱敏审计。仅 T12；不做 T13，不改冻结契约、官方原件或 data/harness.db，不提交推送，不调用真实云模型。

## 已核对证据
main 与本地 origin/main 均为 c1bc5a14afe18bce572179fd8b335600f785f017，初始工作区干净；T10/T11 DONE。已复核 TASKS、README、业务契约、T10 服务契约及 TaskMemory/FinanceAgent/AgentRuntime/InvoiceWriteService 和相关测试。沿用现有精确授权、一次性授权、operationId 幂等及只读 recover。

## 接口与安全
仅 /api/demo/session GET/POST/DELETE，/tasks GET/POST，/tasks/{id} GET，/tasks/{id}/turn POST，/tasks/{id}/supplement POST，/audit GET。HttpSession 保存 DemoPrincipal 和 CSRF；POST session 也先 GET 获取 token。operator 固定 U001、viewer 固定 U002；未知字段（含 userId）拒绝。每个 API 校验直接来源、本地 Host/Origin，拒绝 CORS，企业从 permissions 复核。默认绑定 127.0.0.1。普通 turn/补充没有写授权；turn 中独立结构化 approval 只由确认按钮提交，企业/发票/字段/值再确认。缺完整 LLM 三项配置返回 MODEL_NOT_CONFIGURED，Fake 仅测试。

## 存储与失败
数据库 taxagent.database 默认 data/harness.db；实现和验证仅使用临时独立库。SQLite 新建 append-only audit 表及禁止 UPDATE/DELETE trigger，只存请求ID、用户/企业、任务、工具名/callId、月份或发票ID、字段名、operationId、outcome/errorCode/verified/时间；不存金额、完整流水、用途/备注或原话。审计先记录请求再执行；失败不能报普通成功。提交后任务保存失败用 recover 只读核验，返回明确部分完成及可恢复事件，禁止自动重写。

## 顺序与验收
1. 认领及本计划；2. 本地安全入口、模型配置、审计；3. 零依赖 UI；4. 临时 SQLite + Fake HTTP/服务测试；5. 独立 DB 命令、DEMO_SCRIPT.md 和 macOS 手动录屏；6. 真实 JDK21 全量 Maven 验收后记录准确结果并标 DONE。
覆盖 loopback/外部拒绝、角色/未知身份字段、U002 权限、CSRF、会话/任务隔离、查询/追问/恢复、未确认不写/确认一次且 verified、缺模型、审计隔离脱敏/不可更改、提交后状态保存失败 recover。回退仅移除 T12 代码和文档；审计记录不可删除。不回滚财务写入，不清理其他文件。

## 风险与验证
无生产认证；UI 明示本地模拟身份。真实云模型兼容性和手动录屏不冒充已实测；缺配置可演示安全拒绝。测试命令沿用 README 的 JDK21 + 隔离 .m2，全部临时 SQLite。验收结果见下文。

## 验收结果
2026-10-30 12:11:31 +08:00，真实 Java21 全量 Maven：186/0/0/0，BUILD SUCCESS；T12 新增23项，命令与覆盖细目见 TASKS.md T12。最终代码包括审计防 UPDATE/DELETE/REPLACE 和任务状态/恢复审计双重失败响应。先前 JAR 构建与真实浏览器独立DB验收通过；MODEL_NOT_CONFIGURED 提示、身份隔离和精确弹窗取消已核对，DB无财务写入，临时服务已停止。

新增接口说明见 docs/specs/SPEC-20261030-demo-api.md；演示/安全命令和手动录屏见 DEMO_SCRIPT.md。无真实云模型调用或实际视频；这些限制已明示，不阻塞用户要求的录制准备。无T13、无提交推送、无默认库或官方文件变更。无需跨会话继续实现，无新增 handoff。

## 验收后安全修正（completed）
带 taskId 的 request 在 REQUESTED 前按当前 DemoPrincipal 调用 agent.load 预检；拒绝事件不带目标 UUID，taskId=null。恢复跨用户/企业审计不含目标 UUID 的强断言，覆盖 load/supplement/turn；拥有者访问仍可用。另为页面/静态资源/demo API 添加严格同源 CSP 并测试。仅修改 DemoController、DemoSecurity、DemoApiTest 及对应 T12 文档；不改业务服务、数据库、UI 或官方文件，不调用真实模型，不提交推送。

2026-10-30 12:16:23 +08:00，真实 Java21 全量 Maven **187/0/0/0，BUILD SUCCESS**，T12共24项。使用原验收命令，日志 target/t12-security-test.log；准确命令和强断言细目见 TASKS.md 安全修正记录。上方186项为首次验收历史，本次187项为最终安全复验。未修改或清理既有审计/数据库，T13未开展。
