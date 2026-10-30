# T05 只读财务查询

状态：completed；认领人：Coding Agent。

## 范围与契约
依赖 T03/T04 已 DONE。复用 ToolContract 与 PermissionGuard，实现默认读取 data/harness.db 的 Java 服务；Session 仅由可信调用方传入，无认证链路，不新增 HTTP API。每次在同一只读事务校验当前权限，绑定企业及月份起止日期，返回独立发票/流水记录及整数分汇总，日期 LocalDate，复合键保留企业。空结果为零；无权限与存储错误分别抛既有 DomainException。

## 实施与验收
先认领，再实现服务及 T03 临时 SQLite 集成测试，再执行真实 JDK21 全量 Maven 测试。覆盖六组基线、记录数、日期边界、权限矩阵/撤销、未知主体、复合键、运行数据变化和存储失败。验证通过后更新 README/TASKS。

## 边界、风险与回退
不开始 T06/T07/T09，不修改原件、冻结契约、schema、种子或现有实现；不逐条匹配、不提供写功能。数据库不存在必须失败，不创建空库；读取连接使用 SQLite mode=ro。回退只移除新增服务和测试，无数据库迁移。Session 的可信来源由未来认证入口负责。

## 验证证据
真实 JDK21 全量 Maven 测试于 2026-10-30 01:17:09 +08:00 BUILD SUCCESS，87/0/0/0；T05 新增 17 项。首次沙箱临时目录受限，获准沙箱外重跑通过。详见 TASKS.md T05。无需跨会话交接。
