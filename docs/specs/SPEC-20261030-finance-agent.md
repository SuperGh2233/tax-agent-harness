# T10 本地财税编排服务契约

状态：completed；2026-10-30；新增内部服务接线，不改变冻结业务规则或原工具输入。

`FinanceAgent(database, trustedSession, selectedEnterprise, model)` 绑定可信会话与当前企业，构造时检查读取权限。无 HTTP 注册、身份认证或自然语言授权识别；应用调用者负责已验证身份和真实用户意图，禁止从公网/模型 userId 创建可信会话。切用户/企业需重新构造服务，每次记忆/工具访问仍复检当前权限。

方法：`start(goal,pending)`、`tasks()`、`load(id)`、`supplement(id,version,statements)` 复用 TaskMemory；`turn(id,message)` 无写授权；`approvedTurn(id,message,operationId,update,explicitIntent)` 仅供可信入口核对本次明确保存/清空后调用。EXPLANATION 拒绝；授权绑定用户、当前企业、发票、精确 changes 和 operationId，不存入记忆、不跨轮恢复，也不自动执行写入。模型可选择询问、查询或 update_invoice；修改参数不匹配则 CONFIRMATION_REQUIRED，企业不匹配则 PERMISSION_DENIED。模型 JSON 不能带授权、用户身份或成功标识。

原 Runtime 构造器继续仅提供三个只读工具。显式注入 InvoiceWriteService 的构造器额外提供 update_invoice，参数仍为规范 InvoiceUpdate，将 InvoiceKey 的本地引用等价展开以形成自包含 Schema。Limits.allowedTools 继续控制三个只读工具子集；写能力由可信构造器注入控制。生产无关键词分支，OpenAiCompatibleClient 提示模型在保存后重新查询发票和缺口。

返回 `TaskMemory.Turn(task,result)`；`result.history` 含实际工具调用与 `{data,error}` JSON，`UpdateResult.verified` 仅由实际写服务返回。模型 Finish/text 是生成说明，不是写入证明；FINISHED 仅表示模型结束本轮。工具失败后结果 ERROR，模型随后声称成功不会覆盖错误。任务摘要不存完整财务结果，恢复后需重新调用工具。

`recover(operationId,update)` 只查台账及实际值，实时复检写权限。未知结果/超时不得自动重写，empty 也不是重试许可；新尝试须可信入口重新明确授权。重复同 operationId/内容由写服务只回查，冲突拒绝。工具执行无重试。若写已提交但模型或任务保存失败，以 recover 和实时查询为准；任务存储与业务保存不保证跨服务原子性。

构造器只复用 T08/T09 增量建表；不创建或导入业务库。T10 测试仅用临时官方种子库，未迁移运行库。无新增业务存储 Schema。验收：FinanceAgentIntegrationTest，命令与真实结果见 TASKS.md。
