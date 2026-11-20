# T40 真实 JAR 故障注入检查（自动生成，未经人工修改）

- 生成时间：2026-11-20 18:02:33 +0800；JAR：`harness-0.0.1-SNAPSHOT.jar`；openjdk version "21.0.12.1" 2026-08-18 LTS
- 做法：真实 JAR + 官方种子全新临时库 + loopback；模型由本机桩服务（Anthropic Messages 协议）按脚本应答，不调用真实模型、不需要密钥。
- 判定只看程序证据：错误码、桩模型收到的请求、数据库（F002 用途与 invoice_operations 台账）、重启后的 /verify。首次 SIGKILL 后给 invoices 加 UPDATE 触发器，任何第二次写入都会失败。

| 场景 | 检查 | 结果 | 明细 |
|---|---|---|---|
| timeout | 错误码为 MODEL_TIMEOUT | 通过 | 实际 MODEL_TIMEOUT；耗时 60.1s |
| timeout | 模型共被请求 1 次（不自动重发） | 通过 | 实际 1 次；纠正标记 [False] |
| timeout | 在约 60 秒超时处结束 | 通过 | 耗时 60.1s |
| timeout | 失败回复说明真实状态 | 通过 | 应含 ['没有修改任何发票', '不会自动重发']；回复前 120 字：本次请求未成功完成：MODEL_TIMEOUT。本轮没有修改任何发票；银行流水始终只读。系统不会自动重发给模型，可稍后重新发送本轮问题。 |
| timeout | 任务保持开放 | 通过 | 状态 ACTIVE |
| timeout | 数据库未被修改 | 通过 | F002 用途 ''；台账 0 条 |
| rate-limit | 错误码为 MODEL_RATE_LIMITED | 通过 | 实际 MODEL_RATE_LIMITED；耗时 0.1s |
| rate-limit | 模型共被请求 1 次（不自动重发） | 通过 | 实际 1 次；纠正标记 [False] |
| rate-limit | 失败回复说明真实状态 | 通过 | 应含 ['没有修改任何发票', '不会自动重发']；回复前 120 字：本次请求未成功完成：MODEL_RATE_LIMITED。本轮没有修改任何发票；银行流水始终只读。系统不会自动重发给模型，可稍后重新发送本轮问题。 |
| rate-limit | 任务保持开放 | 通过 | 状态 ACTIVE |
| rate-limit | 数据库未被修改 | 通过 | F002 用途 ''；台账 0 条 |
| malformed-twice | 错误码为 MODEL_RESPONSE_INVALID | 通过 | 实际 MODEL_RESPONSE_INVALID；耗时 0.1s |
| malformed-twice | 模型共被请求 2 次（不自动重发） | 通过 | 实际 2 次；纠正标记 [False, True] |
| malformed-twice | 只有重发的那次请求带纠正说明 | 通过 | 纠正标记 [False, True] |
| malformed-twice | 失败回复说明真实状态 | 通过 | 应含 ['没有修改任何发票', '不会自动重发']；回复前 120 字：本次请求未成功完成：MODEL_RESPONSE_INVALID。本轮没有修改任何发票；银行流水始终只读。系统不会自动重发给模型，可稍后重新发送本轮问题。 |
| malformed-twice | 任务保持开放 | 通过 | 状态 ACTIVE |
| malformed-twice | 数据库未被修改 | 通过 | F002 用途 ''；台账 0 条 |
| malformed-once | 错误码为 无 | 通过 | 实际 None；耗时 0.1s |
| malformed-once | 模型共被请求 2 次（不自动重发） | 通过 | 实际 2 次；纠正标记 [False, True] |
| malformed-once | 只有重发的那次请求带纠正说明 | 通过 | 纠正标记 [False, True] |
| malformed-once | 数据库未被修改 | 通过 | F002 用途 ''；台账 0 条 |
| breaker | 错误码为 TOOL_ERRORS_REPEATED | 通过 | 实际 TOOL_ERRORS_REPEATED；耗时 0.1s |
| breaker | 模型共被请求 3 次（不自动重发） | 通过 | 实际 3 次；纠正标记 [False, False, False] |
| breaker | 失败回复说明真实状态 | 通过 | 应含 ['没有修改任何发票', '连续 3 次']；回复前 120 字：本次请求未成功完成：TOOL_ERRORS_REPEATED。本轮没有修改任何发票；银行流水始终只读。已执行 3 次只读查询，其结果不能作为本轮结论。连续 3 次工具调用出错，已停止重试；请核对企业、月份或权限后再提问。 |
| breaker | 任务保持开放 | 通过 | 状态 ACTIVE |
| breaker | 第 3 次工具错误后即停止 | 通过 | 工具结果错误码 ['PERMISSION_DENIED', 'PERMISSION_DENIED', 'PERMISSION_DENIED'] |
| breaker | 数据库未被修改 | 通过 | F002 用途 ''；台账 0 条 |
| lost-response | 保存已提交、模型正等待回复时 | 通过 | 桩模型收到第 2 次请求 True；F002 用途 '办公室采购'；台账 1 条；回给模型的工具结果含 verified=true True |
| lost-response | 客户端没有收到回复（进程被 SIGKILL） | 通过 | 客户端结果 lost RemoteDisconnected |
| lost-response | 重启后任务仍开放（那一轮没有保存） | 通过 | 状态 ACTIVE |
| lost-response | 重启后按 operationId 只读核验为 VERIFIED | 通过 | HTTP 200；VERIFIED |
| lost-response | 重发同一批准：复用台账、不再写入 | 通过 | HTTP 200；错误码 None；verified True；台账 1 条；第二次 UPDATE 会被触发器拒绝 |
| lost-response | 任务等待 F004 补充 | 通过 | 状态 WAITING_USER |
| never-ran | 批准轮在第一次模型请求处中断，尚未写入 | 通过 | 台账 0 条；F002 用途 '' |
| never-ran | 客户端没有收到回复（进程被 SIGKILL） | 通过 | 客户端结果 ('lost', 'RemoteDisconnected') |
| never-ran | 重启后核验为 NOT_FOUND | 通过 | HTTP 200；NOT_FOUND |
| never-ran | 无批准的继续请求被拒绝，发票未改 | 通过 | 错误码 CONFIRMATION_REQUIRED；台账 0 条；F002 用途 ''；回复前 80 字：本次请求未成功完成：CONFIRMATION_REQUIRED。本轮没有修改任何发票；银行流水始终只读。 |
| stream-disconnect | 收到 started 后客户端断开 | 通过 | 断开前收到 'event:status\ndata:{"phase":"started"}' |
| stream-disconnect | 断开后本轮照常完成并核验 | 通过 | 核验 VERIFIED；台账 1 条；F002 用途 '办公室采购'；模型请求 2 次 |
| stream-disconnect | 本轮结果已保存，任务结项 | 通过 | 状态 COMPLETED |
