# T37 创新对比运行结果（自动生成，未经人工修改）

- 生成时间：2026-11-19 17:25:43 +0800
- 模型：`qwen3.7-flash`，协议：`anthropic`，端点主机：`dashscope.aliyuncs.com`
- 每组运行次数：10；判定：数据库中 F002 用途等于批准值、台账恰好 1 条、回读 `verified=true` 且无错误码

| 版本 | 待补字段 | 通过 / 运行 | 失败码分布 | 保存轮平均耗时（秒） |
|---|---|---|---|---|
| before (`f7ffe68`) | F002.purpose | 5 / 10 | CONFIRMATION_REQUIRED×5 | 13.0 |
| after (`target/harness-0.0.1-SNAPSHOT.jar`) | F002.purpose | 10 / 10 | — | 18.3 |
| before (`f7ffe68`) | F002.purpose,F002.remarks | 4 / 10 | CONFIRMATION_REQUIRED×6 | 14.0 |
| after (`target/harness-0.0.1-SNAPSHOT.jar`) | F002.purpose,F002.remarks | 10 / 10 | — | 19.9 |

逐次明细（含模型 `update_invoice` 实际参数与工具序列）见 `results.json`。
