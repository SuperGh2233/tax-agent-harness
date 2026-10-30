# 开发约定

先读 TASKS.md、README.md、docs/BUSINESS_CONTRACT.md，检查 Git 状态并认领单一任务。
不得擅自扩展业务功能、修改官方材料/计划书/冻结契约、覆盖他人变更、提交或推送 Git。

Java 21、Maven 3.6.3+；启动 `mvn spring-boot:run`，测试 `mvn test`。环境配置及隔离缓存见 README.md。
代码：`src/main/java/com/taxagent/harness` 为启动和 HTTP 接口，`src/main/resources` 为配置，`src/test/java` 为测试。
文档：`docs/index.md` 为索引，冻结业务边界见 `docs/BUSINESS_CONTRACT.md`，目标/验收/风险见 `docs/plans/active/`。
进度、阻塞及恢复操作见 TASKS.md 工作记录；若有 `docs/agent-handoff.md`，跨会话先读它。
只有实际验证通过才标 DONE；密钥、构建产物和依赖缓存不入库。
