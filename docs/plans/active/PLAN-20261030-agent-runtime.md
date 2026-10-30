# T07 模型驱动运行时

状态：completed（保留原路径）。认领人：Coding Agent。前置核对：T04 DONE，T07 TODO；Git 原有文件均未跟踪，保留。

目标：以可替换 LlmClient 决策驱动当前请求的查询、追问和结束，通过冻结 Schema 暴露 query_invoices、query_bank_entries、check_invoice_purpose，严格 DTO 校验后交由现有服务事务内检查权限。可信 Session 仅外部注入，不进入模型输入。

范围：服务层、JDK HttpClient 的 OpenAI-Compatible chat/completions adapter、FakeLlmClient 测试。无新依赖，不提供 HTTP 身份认证，不实现 T08/T09/T10、多 Agent、跨会话记忆或写工具，不运行真实模型，不修改官方数据、冻结契约和 Schema。

执行：先认领与计划，再实现接口/循环/adapter，JUnit5 测试及 JDK21 全量 Maven，最后记录证据。模型调用和工具执行均有超时，步数有上限；模型最多一次配置重试，工具不重试。任何工具失败保留结构化结果并反馈模型，最终返回 ERROR，结束文本不覆盖失败。无密钥与模型异常有明确错误，不记录凭证或远端响应正文。

验收：正常多工具与上下文回传、追问/结束、每请求隔离、权限/未知工具/恶意参数拒绝、禁止写工具、步数上限、超时与模型失败、无密钥零网络；临时库验证不写入，原有测试全通过。真实模型未实测须单独声明。

风险与回退：兼容供应商协议差异未作真实验证；超时取消依赖调用方响应中断，线程为 daemon，不保证强杀任意替换客户端。移除新增 runtime 包即可回退，无数据库迁移。生产身份认证与持久化另属后续任务。

验证证据：真实 JDK21.0.12.1 下 `mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test`，2026-10-30 01:24:23 +08:00 BUILD SUCCESS，116/0/0/0（原有94+新增22）。FakeLlmClient 和本机 HTTP stub 覆盖上述验收项；**未调用真实模型，供应商兼容性未实测**。详见 TASKS.md T07 记录。官方原件/计划书哈希符合基线，运行库未写入；未提交推送。下一任务不在本次范围，无待续工作。
