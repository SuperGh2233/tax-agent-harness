# T13 交付与独立复现计划

状态：active / IN_PROGRESS；认领人：Coding Agent；2026-10-30。

## 目标
准备可交付比赛仓库，让干净源码副本按文档使用 JDK21/Maven 测试、打包、核对官方种子、初始化独立库、loopback 启动和浏览 UI。

## 范围
保留原 T13 交付/复现文档工作，并按用户 2026-10-30 明确授权扩展为一次真实模型端到端验收。E2E 仅用于 T12 本地 Demo；使用比赛官方种子作为合成样本、全新独立临时 SQLite、loopback 127.0.0.1；禁止接触既有 data/harness.db 或任何真实客户财务数据。API key 只从本机安全环境读取，不写进仓库、日志、命令记录或 MCP 任务事件。保留 T12/T13 全部未提交变更；不改比赛官方原件、计划书或冻结业务契约；除所选模型协议确需的最小 adapter/测试/文档外，不扩展业务，不提交、推送或生成正式视频。

## 验证
当前真实模型目标为 DashScope `qwen3.8-flash`，使用 Anthropic Messages API；GPT-6 Luna Responses 与 Chat Completions 路径保留。Messages 适配器追加 `/v1/messages`、发送 `max_tokens=8192` 和 `output_config.effort=medium`，完整 content blocks 在请求内续接，并通过 `tool_use_id` 关联工具结果。先用 loopback HTTP stub 验证请求、Schema、工具往返、解析和安全错误；无轮换密钥前不得发起真实请求。Qwen HTTP 与 AgentRuntime 等待上限均为60秒；其他模型仍为15秒，硬上限60秒。实时 E2E 使用比赛合成种子和全新临时库：查询发票/流水并检查用途缺口；记录用户补充但证明发票尚未写入；仅在演示 UI 收到当前轮明确授权后执行一次精确更新；验证工具 verified、数据库回读与脱敏审计；不自动重试。停止服务并确认本测试库操作台账结果，原始 data/harness.db 未变。空缓存在线构建不能由既有缓存结果替代。

## 风险与回退
临时目录/端口需权限；空缓存需要网络，既有缓存验证不能等同首次在线 checkout。T12 未提交，git archive HEAD 会漏掉界面，交付前须用户人工整合并审核。忽略规则不能代替交付文件白名单审查。真实模型、正式视频、最终压缩包及主办方入口等由用户后续完成。若发现阻止基本复现的代码故障，记录并停止，不扩大范围。回退只撤销本次 T13 文档新增段落和忽略项，保留 T12 内容。

## 实际结果与下一步
干净源码副本（不含data/.env/target/.m2）在既有隔离缓存条件下：JDK21.0.12.1/Maven3.10.0，2026-10-30 12:22:41全量187/0/0/0，package成功；种子核对PASS，新DB初始化2/2/4/40/48/5。127.0.0.1:18083健康200/UP、HTML/JS/CSS与真实浏览器UI通过，CSRF会话及MODEL_NOT_CONFIGURED安全状态通过，invoice_operations=0；服务已停止，最终连接失败。沙箱失败与获准重跑、准确命令和本地日志路径详见TASKS的T13工作记录。

REPRODUCE/RELEASE_CHECKLIST已包含在线/离线复现、排障、官方要求对照、人工录屏及材料缺口；README/index入口已追加。忽略规则检查和git diff --check通过；原件/计划书/冻结契约/现有DB及全部非T13文件SHA256未变，原有T12工作保留。不提交、不推送、不调用真实模型、不生成视频。

保持 active / IN_PROGRESS：空缓存首次在线下载及真正全新 checkout 未证实。当前真实模型目标为 DashScope qwen3.8-flash（Anthropic Messages）；GPT-6 Luna Responses 与 Chat Completions 仍受支持。本轮完成 Messages 适配器和离线 stub 验证，未检查/使用聊天中暴露的凭证，也未发起真实模型请求。真实 E2E 待该密钥撤销且用户在本机安全配置轮换密钥后，在官方合成样本和独立临时库完成。正式设计短稿、去敏关键记录、手动视频与最终包/主办方信息仍按检查单处理。

## Responses 最小实现验收
完整 `/responses` endpoint 显式选择协议，不自动回退；原 Chat Completions 保留。复用 JDK HttpClient/Jackson、原 system instructions 和工具 schema；store=false、parallel_tool_calls=false。每步仅允许一个完整 function_call 或严格 Ask/Finish JSON；拒绝 malformed/incomplete/多调用。内部 Call 携带不序列化的 output items，history 续接重放 reasoning 等原项，再追加精确 call_id 的 function_call_output；用户历史/审计不含供应商续接元数据，写授权不变。保留 HTTPS/HTTP loopback、拒绝重定向、60秒上限和安全错误。回退仅移除本轮 adapter/测试及文档段落。聚焦命令：JDK21、隔离缓存、`mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" -Dtest=OpenAiCompatibleClientTest,OpenAiResponsesClientTest test`，仅 loopback stub。

2026-10-30 14:07:33 +08:00，JDK21.0.12.1，以上聚焦命令 **5 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**（Responses 3、Chat Completions 2）。首次编译的 JsonNode 泛型推断错误已修正；随后沙箱内 5/0/3/0，3项因 loopback bind 的 Operation not permitted 阻塞，获准沙箱外原命令重跑得到上述通过结果。报告在 target/surefire-reports。本轮未跑全量、未访问外部模型、未读取环境值、未操作任何 SQLite。覆盖请求字段/工具 schema、单调用解析、reasoning及完整output重放、精确call_id及工具错误结果、不泄漏续接元数据、最终Ask/Finish、malformed/truncated/多调用拒绝、缺key零网络、关联不符零网络、重定向拒绝和安全错误。真实云端兼容性与 E2E 范围已获用户授权，待本机安全配置凭证后执行；T13 IN_PROGRESS。

## 当前目标更新
当前实测目标 DashScope qwen3.8-flash：独立 Anthropic Messages adapter，base 后追加 /v1/messages，max_tokens=8192、output_config.effort=medium。保留 Luna Responses 和 Chat Completions，无回退。完整 content 内部重放并关联 tool_result；严格解析、隐藏思考、拒绝重定向。Qwen HTTP 与 AgentRuntime 等待上限均为60秒，其他模型保留15秒默认；全局硬上限60秒。仅 loopback 测试，不触碰 SQLite；真实 E2E 等待撤销泄露密钥并本机配置替换值。验收覆盖请求、续接、解析和安全边界。

## DashScope 本轮验证结果（2026-10-30）
T13 保持 IN_PROGRESS。当前目标 DashScope qwen3.8-flash，旧 Luna/Chat 支持保留。
精确命令：
```zsh
JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" -Dtest=AnthropicMessagesClientTest,OpenAiCompatibleClientTest,OpenAiResponsesClientTest test
```
初次沙箱执行因 loopback bind 被拒：7 tests / 0 failures / 4 errors；获准沙箱外离线重跑。最终 2026-10-30 14:47:51 +08:00 BUILD SUCCESS：8 tests / 0 failures / 0 errors / 0 skipped（Messages 3、Responses 3、Chat 2）。覆盖空 thinking/signature 原样重放、关联 tool_result、内部状态不序列化、请求字段、严格解析、重定向目标零访问及错误响应标记不泄漏。仅本地 HTTP stub；未读取环境凭证、未访问外网、未操作任何 SQLite。无提交或推送，既有 T12/T13 改动保留。
14:47 基线测试记录的超时策略为 HTTP 与 AgentRuntime 均15秒；随后本轮调整为 Qwen 专用60秒。该基线没有验证真实延迟，也没有运行真实 E2E。

## Qwen 超时跟进验证（2026-10-30）
DemoConfiguration 对 `/anthropic` endpoint 使用60秒 HTTP 超时；FinanceAgent 对 AnthropicMessagesClient 使用60秒 AgentRuntime 上限，其他模型保留15秒默认，系统上限仍为60秒。新增 FinanceAgentTimeoutTest 覆盖 Qwen/其他模型两种运行时值。

首次 Maven 尝试因 ServerFS 编辑保留源文件旧 mtime、使用旧编译 class，在测试执行前编译失败（0项执行）。刷新四个已修改源文件的 mtime（未改内容）后，使用 JDK21 离线命令：
```sh
JAVA_HOME=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home PATH=/Users/a123/Library/Java/JavaVirtualMachines/jdk-21.0.12.1+1/Contents/Home/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin /opt/homebrew/bin/mvn -o -B -ntp -Dmaven.repo.local='/Users/a123/Projects/财税 harness/.m2/repository' -Dtest=AnthropicMessagesClientTest,OpenAiCompatibleClientTest,OpenAiResponsesClientTest,FinanceAgentTimeoutTest test
```
2026-10-30 15:33:45 +08:00：**9 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS**（Messages 3、Responses 3、Chat Completions 2、timeout 1）；`git diff --check` 通过。仅 loopback HTTP stub；未访问外网、真实模型、环境变量/密钥或 SQLite。没有清理 target、提交或推送。真实 E2E 仍待撤销聊天中暴露的密钥并在本机安全设置轮换值；范围限合成数据、独立临时 SQLite 与 loopback。

## Qwen MODEL_FAILURE 排查与修复范围（2026-10-30）

- 现象：本机演示 UI 已进入 ACTIVE 任务；发送普通问候后 UI 显示 MODEL_FAILURE。请求没有执行任何财务工具或写入。
- 证据：按用户此前授权，从本机 `.env` 对 DashScope Anthropic Messages API 发出合成最小请求。普通请求及带业务工具 Schema 的请求均返回 HTTP 200；带工具 Schema 时 Qwen 偶尔未遵循提示词中的 Ask/Finish JSON 约定，现有适配器因严格解析而降级成 MODEL_FAILURE。追加 Anthropic `output_config.format.type=json_schema` 的工具请求成功返回结构正确的 `query_invoices` tool_use。随后本地 Harness 成功执行该只读工具，但 Qwen 的续接回复为普通文本，仍被 JSON-only 解析器判为 MODEL_FAILURE；用合成 tool_result 可直接复现。没有发生财务写入。
- 目标：保留 Qwen 官方支持的严格 JSON Schema；合法 Ask/Finish JSON 正常解析；无工具且 `end_turn` 的普通文本安全映射为 Finish，避免把自然语言回答误判为协议故障。tool_use 的结构、允许工具和写入授权仍严格校验，不把解析错误转换为工具执行。
- 验收：HTTP stub 断言请求中的 `output_config.format` Schema；验证 Ask/Finish JSON、普通最终文本、工具调用及历史续接，畸形 tool_use 继续失败，密钥脱敏不变。通过本地 Demo API 做一次 Qwen 只读查询并确认 query 工具执行与最终回复均成功、没有 update_invoice 调用。
- 初步验收：JDK21 定向 `AnthropicMessagesClientTest` **3/3 通过**（2026-10-30 17:01:33 +08:00）；离线 Maven package `BUILD SUCCESS`（17:03:22）。DashScope 结构化输出可与工具调用并用；截图中的问候任务经本地 Demo API 返回 HTTP 200/FINISHED/0 工具，确认首轮响应修复。随后只读查询再次发现工具调用后的普通最终文本解析问题；当前修复仍进行中。完整财税查询/保存场景真实模型 E2E 未完成。
- 最终修复验收：JDK21 定向 `AnthropicMessagesClientTest` **3/3 通过**（2026-10-30 17:27:37 +08:00）；离线 Maven package `BUILD SUCCESS`（17:28:46）。17:29 重启后，通过本地 Demo API 在同一活动任务和隔离临时库完成 Qwen 只读查询：HTTP 200、无 error、FINISHED，实际调用 `query_invoices`，0 个工具错误，没有调用 `update_invoice`。问候及查询均未写财务数据；首页 HTTP 200，`/api/health` 为 `UP`。完整的多场景查询、补充和授权保存 E2E 仍未完成。
- 回退：仅撤销本节、对应 Messages 适配器变更及其定向测试；不清理既有未提交 T12/T13 文件、不重建或修改 demo 数据库、不提交/推送。

## Qwen 联调稳定性跟进（2026-10-30）

- 新证据：17:41:59 的本机演示请求在 17:42:06 记为 `MODEL_FAILURE`，没有工具事件；隔离 demo DB 的 `invoice_operations` 仍为 0。当前 Messages 适配器将未分类的响应解析异常折叠为 `MODEL_FAILURE`，无法区分截断、无效响应与连接故障。
- 官方接口边界：[DashScope Anthropic-compatible Messages API](https://help.aliyun.com/en/model-studio/anthropic-api-messages) 说明 `qwen3.8-flash` 的 `max_tokens` 是思考与答复 token 总额，达到上限会返回 `stop_reason=max_tokens`；当前 4096 上限可能使响应在产生可解析决策前结束。
- 最小改进：把输出上限提高至 8192；将 token 截断、无效响应、传输失败、401/403、429 分别映射为不含供应商正文/密钥的安全错误码；支持单个合法 `tool_use` 同时带普通文本块，仍拒绝多工具、截断调用、无效参数并保留后端权限与当前轮写入授权校验。
- 本轮边界：不发起新的云端请求、不访问插件、不操作数据库、不读取或记录密钥。编译打包后重启本机隔离 demo 服务；真实 Qwen 行为仍待安全配置新凭证后的本机 E2E 确认，T13 保持 IN_PROGRESS。
- 实际完成：`AnthropicMessagesClient` 已改为 8192 token 上限；在 `stop_reason=max_tokens` 时安全终止并返回 `MODEL_OUTPUT_LIMIT`；将响应格式、网络、认证、限流和其他 HTTP 错误分开映射；单个 `tool_use` 可附带文本块。写入授权、工具白名单、参数校验、拒绝截断调用及不跟随重定向保持不变。UI 对新错误码给出中文说明；本节和演示 API 规范已同步。
- 验收：JDK21 离线 `mvn -o -B -ntp -Dmaven.repo.local='/Users/a123/Projects/财税 harness/.m2/repository' -DskipTests package` 于 18:14:28 BUILD SUCCESS；20 个生产源文件和 16 个测试源文件编译通过，测试执行跳过；`node --check src/main/resources/static/demo.js` 与 `git diff --check` 通过。18:14 重启本机演示服务 PID 23216，`/api/health` 返回 `{"status":"UP"}`，实际页面已加载新错误提示。未调用 Qwen、未读密钥、未访问插件或操作 SQLite。真实模型响应兼容性尚未验证，T13 保持 IN_PROGRESS。
