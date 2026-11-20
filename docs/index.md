# 文档索引

> **队友接手请先读 [开发交接说明](agent-handoff.md)**，再阅读业务契约、工具契约和任务台账。交接说明包含当前 Git 工作区、测试证据、未完成事项和安全边界。

| 路径 | 状态 | 用途 |
|---|---|---|
| [开发交接说明](agent-handoff.md) | current | 当前 Git 工作区、测试证据、未完成事项和接手顺序 |
| [BUSINESS_CONTRACT.md](BUSINESS_CONTRACT.md) | completed | T01 冻结业务边界，只读保留原路径 |
| [FINAL_ROUND_CONTRACT.md](FINAL_ROUND_CONTRACT.md) | completed | 复赛契约修订（2026-11-18 批准）：替代/保留的初赛条款、新增约束与已定事项；与初赛契约冲突时以此为准 |
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

| [T12 计划](plans/active/PLAN-20261030-t12-ui-audit-demo.md) | completed | 本地 UI、安全会话、脱敏审计与演示验证 |

| [T12 演示 API](specs/SPEC-20261030-demo-api.md) | completed | 本地模拟会话、CSRF、最小接口和审计失败语义 |
| [T12 演示与录屏](../DEMO_SCRIPT.md) | completed | 独立 demo DB、安全启动、React 录屏步骤 |
| [审计保留策略](specs/AUDIT_RETENTION.md) | active | append-only、导出、归档和保留周期 |
| [依赖安全扫描](specs/DEPENDENCY_SECURITY.md) | active | OSV 运行时依赖 CVE 扫描、修复版本与抑制记录 |
| [关键运行记录索引](evidence/README.md) | current | 四份运行记录、22 张截图、数字口径表（缺口随写入变化，必须带时点） |
| [提交前测试报告](evidence/TEST_REPORT_20261111.md) | archived | 初赛时点：252 项回归、API 受控保存/幂等、负例、浏览器 E2E、日期序列化修复 |
| [演示重录记录](evidence/DEMO_RERUN_20261111.md) | current | 先保存后补充的六段实测与 14 张分镜截图 |
| [风险修复记录](evidence/FIXES_20261111.md) | current | 规则变更即时生效、`agent_trace` 持久化、`finish` 伪工具归一化 |
| [真实 E2E 记录（历史）](evidence/REAL_E2E_20261106.md) | archived | 2026-11-06 千帆 DeepSeek API 三场景；当前口径为 `qwen3.7-flash` |
| [T37 创新对比](evidence/T37_INNOVATION_20261119_run3/) | current | 批准轮保存载荷由程序生成：改进后 20/20 vs 改进前 9/20（`qwen3.7-flash`；run1/run2 为早期运行） |
| [T38 长对话](evidence/T38_LONG_CONVERSATION_20261120/) | current | 真实模型 30 轮：压缩、限制与更正保留、`recall_context` 取回原文 |
| [T39 大工具结果](evidence/T39_LARGE_RESULT_20261120/) | current | 真实模型：>6000 字符结果裁剪后分页取回 |
| [T39 全新检出](evidence/T39_FRESH_CHECKOUT_20261120/) | current | 新克隆 + 空 Maven 缓存在线构建、启动、离线复跑（macOS） |
| [T40 故障注入](evidence/T40_FAULT_INJECTION_20261120/) | current | 真实 JAR + 桩模型 8 场景 40 项：超时、429、畸形回复、熔断、响应丢失、流式断开 |
| [T41 Maven 3.6.3](evidence/T41_MAVEN_363_20261120/) / [Wrapper](evidence/T41_WRAPPER_20261120/) | current | 文档最低版本组合与仓库 Wrapper 的本机实测 |
| [T41 CI 三平台](evidence/T41_CI_20261120/) | current | GitHub Actions Ubuntu / Windows / macOS + Maven 3.6.3 四作业全绿 |
| [T42 Windows 短路径](evidence/T42_VITE_SHORT_PATH_20261120/) | current | 8.3 短名路径下前端构建失败的产品侧修复与 Windows 复测 |
| [T43 CI 复跑](evidence/T43_CI_20261120/) | current | action 升至 Node 24 版本后四作业全绿，Windows 仍输出 `[vite] root` 诊断行 |

| [T13 计划](plans/active/PLAN-20261030-t13-release-reproduction.md) | active | 独立复现、经用户批准的真实模型E2E范围、证据与待验证边界 |
| [独立复现](../REPRODUCE.md) | active | JDK21首次在线/后续离线构建、新库、安全启动及排障 |
| [交付检查单](../RELEASE_CHECKLIST.md) | active | 官方要求对照、白名单、人工设计/记录/录屏待办和T13变更清单 |
| [初赛提交说明](SUBMISSION_NOTES.md) | active | 截止时间、模型口径（统一 `qwen3.7-flash`，官方未指定型号）、构建/离线、视频脚本和提交门禁 |
