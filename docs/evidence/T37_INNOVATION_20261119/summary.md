# T37 创新对比运行结果（自动生成，未经人工修改）

- 生成时间：2026-11-19 14:27:33 +0800
- 模型：`qwen3.7-flash`，协议：`(by endpoint)`，端点主机：`dashscope.aliyuncs.com`
- 每组运行次数：5；判定：数据库中 F002 用途等于批准值、台账恰好 1 条、回读 `verified=true` 且无错误码

| 版本 | 待补字段 | 通过 / 运行 | 失败码分布 | 保存轮平均耗时（秒） |
|---|---|---|---|---|
| before (`f7ffe68`) | F002.purpose | 3 / 5 | CONFIRMATION_REQUIRED×2 | 14.8 |
| after (`HEAD`) | F002.purpose | 0 / 5 | CONFIRMATION_REQUIRED×5 | 12.6 |
| before (`f7ffe68`) | F002.purpose,F002.remarks | 1 / 5 | CONFIRMATION_REQUIRED×4 | 12.1 |
| after (`HEAD`) | F002.purpose,F002.remarks | 1 / 5 | CONFIRMATION_REQUIRED×3, INVALID_ARGUMENT×1 | 11.6 |

逐次明细（含模型 `update_invoice` 实际参数与工具序列）见 `results.json`。
