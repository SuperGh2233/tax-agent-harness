# T08 任务记忆服务契约

状态：completed。仅可信 Java 后端调用 `TaskMemory(Path)`；要求已有初始化数据库，构造仅幂等创建 task-memory.sql 的独立表/索引，无种子写入。无 Spring 自动装配、HTTP 或模型记忆工具。

每次服务访问均在同一事务按 Session.userId + enterprise 校验当前 READ 权限，并绑定该复合作用域；只读财务权限允许维护自己的任务/偏好，不授予财务写权。身份不能由 ToolContract.decode 解码。无权返回 PERMISSION_DENIED，无身份 AUTHENTICATION_REQUIRED，跨作用域任务不存在为 NOT_FOUND。缺库/存储故障为 STORAGE_ERROR。

`start(session,enterprise,explicitGoal,pending)` 创建 ACTIVE 任务；`tasks` 查询本作用域全部任务，`load` 返回任务及本作用域偏好。部分唯一索引拒绝同作用域第二个 ACTIVE/WAITING_USER，返回 CONFLICT。不同用户/企业可各有任务。

任务保存 id、状态、乐观版本、明确目标、待补字段、用户补充（value/userRequest 原话证据）、模型最近追问、最近一轮工具名及 SUCCESS/错误码摘要。不保存旧工具金额、用途或规则正文，不从模型回答提取事实。

`supplement(...,version,changes)` 仅接受已声明待补或已补字段，覆盖旧值、清除对应待补并回 ACTIVE；可更正但不修改发票。可信调用方核对原话与字段值，不把模型推断传入此入口。`cancel` 关闭任务并释放唯一槽位。终态不可继续/更正，旧版本返回 CONFLICT。

`continueTask(...,message,runtime)` 每轮重新校验/加载上下文，模型照常决定工具/追问/结束；Runtime 限制工具企业为当前任务企业，原有 user_id 严格解码拒绝不变。Ask→WAITING_USER，Finish→COMPLETED，错误→ACTIVE（可重试）；追问不自动增补结构化字段，已知 pending 由可信调用方创建时声明，动态补充可直接在本轮 message 提供。模型生成的 Finish 仅表示本次只读任务结束，不是业务保存证明。

模型运行期间不持有数据库事务，结束后复检权限与任务版本；期间更正/取消会使旧轮 CONFLICT，不覆盖新状态。并发只读工具可能重复执行，但无业务写入。服务重建后从 SQLite 读取相同任务，跨对话不依赖 conversation ID。取消后继续拒绝，历史仍可查询。

`preferences/putPreference/deletePreference` 支持查看、新增、更正、删除；写入须传明确长期偏好 Input(value,userRequest)，不自动提取。偏好无模型写入口；内容作为用户声明提供上下文，不覆盖当前业务查询和本轮更正，不构成新会话授权。删除幂等。可信调用方承担身份签发、明确用户输入核对；本服务不实现自然语言授权识别。

迁移为 additive CREATE IF NOT EXISTS，仅操作 agent_tasks/agent_preferences；回退停用服务并保留表，不删除数据。状态及版本同时存列/JSON，所有写入由服务同事务同步。
