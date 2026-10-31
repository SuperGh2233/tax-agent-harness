# T12 本地演示 HTTP 与审计契约

状态：completed；2026-10-30。新增演示入口，不修改 T10 或冻结工具/业务契约。

## 信任与配置

本地模拟身份，不是生产认证。服务器固定 operator → U001、viewer → U002，仅此 allowlist，浏览器/模型不能提供可信 userId。`DemoPrincipal` 只由服务器创建并置于 HttpSession，每次构建 FinanceAgent 以及任务/工具/审计访问均复核数据库 permissions。Cookie HttpOnly、SameSite Strict、仅 cookie 跟踪；切换身份/企业成功后销毁旧会话、换新会话与 CSRF token。任务恢复归属 user+enterprise；相同用户再次进入可恢复持久任务，未登录或其他用户/企业不可访问。

所有 /api/demo/ API 必须直接来自 IPv4/IPv6 loopback，Host 为 loopback 或 localhost；Origin 若存在必须与当前 scheme/host/port 精确一致，拒绝 cross-site / Forwarded / X-Forwarded-For / X-Forwarded-Host，禁止代理建立信任。无 CORS，不允许 OPTIONS；禁用 forwarded headers 处理。无查询参数。POST/DELETE 均要求 HttpSession 中的 X-CSRF-Token，初次 GET session 获取 token；本地命令行仍必须获取 cookie/token。默认绑定 127.0.0.1，非生产登录入口。

`taxagent.database` 默认 data/harness.db，也可由 TAXAGENT_DATABASE 指定；构造器不导入种子，演示须先用独立 DB 初始化命令。启动不迁移默认库；第一次实际请求才复用 T08/T09 及新增审计表。LLM_ENDPOINT/API_KEY/MODEL 三项都显式提供才创建模型客户端；endpoint 路径以 `/anthropic` 结尾时使用 Anthropic Messages 适配器，否则使用 OpenAI Responses/Chat Completions 客户端。缺任一项为 MODEL_NOT_CONFIGURED，无 Fake fallback，密钥不记录。

当前 T13 真实测试目标为 DashScope `qwen3.8-flash`，endpoint 为 `https://dashscope.aliyuncs.com/apps/anthropic`，适配器追加 `/v1/messages` 并使用 `x-api-key`。发送 `max_tokens=8192`、`output_config.effort=medium` 与现有工具 Schema；Qwen 的 max_tokens 同时限制思考和答复。assistant content 按原样私下续接，并以精确 `tool_use_id` 关联 `tool_result`；单个有效 tool_use 可与普通文本块并存，多工具、截断调用及无效参数仍拒绝。thinking/signature 不进入可见 history/审计；无工具结束输出接受 Ask/Finish JSON 或普通文本。DashScope 错误以安全码区分输出上限、无效响应、传输失败、认证失败、限流及其他 HTTP 错误，不返回供应商正文或密钥。DashScope HTTP 与 AgentRuntime 等待上限均为60秒；其他模型仍为15秒，系统硬上限60秒。OpenAI Responses（GPT-6 Luna Max）和 Chat Completions 路径保留，按 endpoint 显式选择、无自动回退。仅 HTTPS 或 HTTP loopback、拒绝重定向。凭证仅本机安全输入；本轮只用 loopback stub 离线测试。用户已授权真实 E2E，但聊天中提供的密钥已暴露，须先撤销并只在本机设置轮换密钥；之后测试仍限官方合成样本、全新临时 SQLite 与 loopback，禁止 data/harness.db/真实财务数据。

## 最小 API

JSON 字段使用 camelCase；未知字段（含嵌套 userId/user_id）、无效正文拒绝。无财务 update 独立端点，无任意身份 DTO，无模型授权签发工具。

| 方法 / 路径（共同前缀 /api/demo） | 输入与输出 |
|---|---|
| GET /session | 返回 csrfToken、profile、enterprise、identityNotice；未选择时身份为空 |
| POST /session | `{profile:"operator"或"viewer",enterprise:"C001"或"C002"}`；实际 permissions 检查后重建会话，返回新 token；U002/C002 为403 |
| DELETE /session | 销毁会话，保留隔离的持久任务；需 token |
| GET /tasks | 当前 user+enterprise 的 TaskMemory.Task 列表 |
| POST /tasks | `{goal,pending:["F002.purpose"]}`；pending 可空，返回 task；每用户企业最多一个未完成任务 |
| GET /tasks/{UUID} | 返回 `{task,preferences}`；恢复文本不是当前业务证据或授权 |
| POST /tasks/{UUID}/cancel | `{version,confirmed:true}`；需 CSRF、可信会话与当前任务版本，仅 ACTIVE/WAITING_USER 可取消；保留历史、不撤销财务写入；终态或过期版本返回409 CONFLICT |
| POST /tasks/{UUID}/supplement | `{version,statements:{"F002.purpose":{value,userRequest}}}`；仅用户手填明确值及原话，无模型提取；仅现有待办/已补字段可更正，乐观版本检查；不保存发票 |
| POST /tasks/{UUID}/turn | `{message,approval?}`；无 approval 普通聊天；确认时另传 `{operationId:UUID,invoiceId,changes:{purpose或remarks:精确值},confirmed:true}`，企业只取会话；调用 approvedTurn，模型仍须选择精确 update_invoice；空白修改明确按 CLEAR_NOW 处理 |
| GET /audit | 当前用户企业最近200条脱敏摘要，按id倒序 |

turn 返回 `{turn,verification,errorCode,message,operationId}`。turn 为 T08 的 `{task,result}`；result.history 是真实 Runtime 调用与完整 `{data,error}` JSON，当前页面展开显示；不写入历史审计。模型 text/FINISHED 不是保存证据。只有实际 update_invoice 工具 verified=true，且 FinanceAgent.recover 只读 DB/台账回读一致，verification 才提供 verified 写入证据。

## 错误和恢复

边界错误返回 400/401/403/404/409/500 与安全 errorCode；不输出底层异常、密钥或供应商正文。运行阶段模型/工具错误在 turn.result.status=ERROR 和 errorCode 显示，即使 HTTP200 也不是业务成功。缺模型返回 MODEL_NOT_CONFIGURED，任务保持 ACTIVE。DashScope Messages 适配器使用 `MODEL_OUTPUT_LIMIT` 标记 `stop_reason=max_tokens`，`MODEL_RESPONSE_INVALID` 标记无法识别的响应，`MODEL_TRANSPORT_ERROR` 标记连接失败，`MODEL_AUTH_FAILED` 标记 HTTP 401/403，`MODEL_RATE_LIMITED` 标记 HTTP 429，其他非成功状态为 `MODEL_HTTP_ERROR`。错误码不附带供应商响应正文、异常消息或凭证。结构化 confirmed=false 拒绝；聊天、补充、模型声称用户同意都无授权。

审计请求写入先于变更，审计不可用则不执行。若变更已提交而后续审计失败，返回 AUDIT_STORAGE_ERROR 及可得的 DB 只读核验，不宣称整体成功。任务保存异常时，已批准请求以同 operationId/update 调用 recover；发现已提交则返回 TASK_STATE_SAVE_FAILED、“财务写入已核验但任务状态保存失败”及 verification，追加 RECOVERABLE_TASK_STATE_FAILURE；不自动重试。recover 空记录或失败均不可推定可重写。客户端保留 operationId 排查，重复同 operationId/内容依旧由现有台账只回查。

## Append-only 审计

SQLite demo_audit：自增id、user_id、enterprise_id、request_id、task_id、tool、call_id、month、invoice_id、fields、operation_id、outcome、error_code、verified、time（UTC）。元数据包含请求发起/结果、任务操作、真实工具及恢复事件；工具参数只抽取月份/发票ID/字段白名单，无任意 payload 列，无金额、完整流水、用途、备注、用户原话或模型文本。标识符有格式限制。UPDATE/DELETE 及同id INSERT OR REPLACE triggers 拒绝改删覆盖，API 无改删入口；这是应用内 append-only，持有 DB 文件权限的本机管理员仍可改变 schema，不提供外部防篡改承诺。

TaskMemory 自身仍按 T08 保存任务目标、明确补充及原话；InvoiceWriteService 按 T09 保存精确操作台账以幂等/回读。这些不是新增审计副本。用户/企业审计隔离在同一事务复核 permissions，模型/浏览器不能指定 audit user。

带 taskId 的 request 在任何 REQUESTED 审计前以当前 DemoPrincipal 调用 FinanceAgent.load 预检所有权/权限；NOT_FOUND/PERMISSION_DENIED 拒绝事件仅保留 requestId/action/errorCode，taskId=null，不保存目标 UUID。load/supplement 共用此入口；turn 保持先 load 后记录。权限已撤销而无法追加审计时仍拒绝访问，不以审计授予权限。既有 append-only 记录不由本次修正迁移或删除。

页面、静态资源和 demo API（含拒绝响应）返回严格 CSP：`default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'`。无外部资源，API 原有来源和 CSRF 限制不变。

验收见 DemoApiTest、TASKS.md 和 T12 计划；2026-10-30 安全修正后真实 JDK21 全量187/0/0/0通过，恢复目标任务 UUID 隔离强断言并验证 CSP；原有服务契约不改。

## 当前真实测试目标：DashScope Messages
当前目标为 `LLM_ENDPOINT=https://dashscope.aliyuncs.com/apps/anthropic`、`LLM_MODEL=qwen3.8-flash`；独立适配器追加 `/v1/messages`，使用 x-api-key、max_tokens=8192、output_config.effort=medium，自动工具选择。Qwen 的 max_tokens 计入思考与答复。保留 GPT-6 Luna Responses 和既有 Chat Completions，无自动回退。思考及签名仅作为内部续接状态，不进入结果或审计。Qwen 的 HTTP 与 AgentRuntime 每次调用上限均为60秒；其他模型仍为15秒，系统硬上限60秒。

此前粘贴的凭证已暴露，必须先在供应商控制台撤销；替换凭证仅在本机 zsh 隐藏输入，勿粘贴到聊天：
```zsh
export LLM_ENDPOINT=https://dashscope.aliyuncs.com/apps/anthropic
export LLM_MODEL=qwen3.8-flash
read -rs "LLM_API_KEY?输入已轮换的新密钥（隐藏）："; print
export LLM_API_KEY
# 完成后清除
unset LLM_API_KEY
```
用户已授权合成数据及独立临时 SQLite。此次仅允许 loopback HTTP stub，不访问外网、不操作任何 SQLite；真实 E2E 等待轮换密钥在本机配置。T13 保持 IN_PROGRESS。上述目标替代文档早先的 Luna 当前目标描述，Luna 支持继续保留。

注意：Qwen 的单次 HTTP 请求和 AgentRuntime 等待上限为60秒；其他模型默认仍为15秒。尚未测得真实云端延迟，超过上限会返回超时。

## T15 单实例并发与未知写入边界
既有 DemoController 使用固定64个进程内 lock stripes，按可信 principal 的 userId+enterprise 选择。turn、cancel、supplement 共用此锁，覆盖读取、模型/工具执行、任务CAS与审计；同一用户企业即使来自不同 HttpSession 也串行。任务作用域、权限、CSRF和数据库乐观版本校验保留。取消等待已开始的turn结束后重新检查版本/终态，已完成任务返回409。条带碰撞仅降低并发；这是 loopback 单 JVM demo，不提供跨 JVM 或不响应中断的超时 worker 强制停止保证。
默认静态页与React共用 sessionStorage 键 taxagent-pending-operation，仅保存 {id,taskId,unknown:true}。批准请求发送前持久化；只有该请求响应 operationId 精确匹配且 verification.verified===true 才清锁。网络/解析/HTTP错误及未核验结果均禁止再次approve，重载恢复警告，只读查询可用，不自动重试。
