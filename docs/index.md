# 文档索引

| 路径 | 状态 | 用途 |
|---|---|---|
| [BUSINESS_CONTRACT.md](BUSINESS_CONTRACT.md) | completed | T01 冻结业务边界，只读保留原路径 |
| [T02 计划](plans/active/PLAN-20261029-backend-bootstrap.md) | completed | 后端骨架目标、验收和风险，保留原路径 |

任务执行证据见根目录 TASKS.md，启动见 README.md。新增文档按类型存放，本次不重组冻结契约。

| [T03 计划](plans/active/PLAN-20261029-official-import.md) | completed | 初始化范围与验收 |
| [T03 存储](specs/SPEC-20261029-seed-storage.md) | completed | schema、幂等及失败行为 |

| [T04 计划](plans/active/PLAN-20261030-tool-contracts.md) | completed | T04 范围、验收、回退和实际验证记录 |
| [T04 工具契约](specs/TOOL_CONTRACT.md) | completed | 工具输入输出、规范 JSON Schema、错误码、可信身份与权限边界；实现证据见 TASKS.md |

| [T05 计划](plans/active/PLAN-20261030-read-only-finance.md) | completed | 只读财务服务范围与验收 |
| [T06 规则服务](specs/TOOL_CONTRACT.md) | completed | 规则查询、用途检查及管理边界；实现证据见 TASKS.md |
| [T07 计划](plans/active/PLAN-20261030-agent-runtime.md) | completed | 模型工具循环、边界与离线验收，真实模型未实测 |

| [T08 计划](plans/active/PLAN-20261030-task-memory.md) | completed | 任务记忆、状态恢复与验收 |
| [T08 存储契约](specs/SPEC-20261030-task-memory.md) | completed | 可信服务调用、隔离与状态转换 |
| [T09 安全写入](specs/TOOL_CONTRACT.md) | completed | 一次性可信授权、操作台账、事务回读和超时只查询恢复；验收见 TASKS.md |
| [T10 计划](plans/active/PLAN-20261030-end-to-end.md) | completed | 三场景端到端范围、风险与验收，保留原路径 |
| [T10 服务契约](specs/SPEC-20261030-finance-agent.md) | completed | 可信本地入口、模型写工具、跨轮授权与结构化证据 |
| [T11 回归与故障注入计划](plans/active/PLAN-20261030-t11-regression-fault-injection.md) | completed | 权限隔离、规则版本、超时/重试、提交边界故障注入；JDK 21 全量 163 项通过，详见 TASKS.md |
