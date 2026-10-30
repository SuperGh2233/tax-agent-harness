# T04 工具契约 v1

状态：completed；内部接口设计，依据冻结 BUSINESS_CONTRACT.md 和 T03 schema，不改变官方业务边界。

本任务仅提供不可变输入输出、参数校验、错误码及 SQLite 权限守卫。顺序：定义契约→实现 DTO/守卫→JUnit 5→JDK21 Maven 验收。无业务查询、规则执行、Agent、任务存储或发票写入；无需数据库迁移，回退仅移除本任务新增代码。

## 身份与权限

所有模型工具参数禁止 user_id、权限、授权标志等额外字段。唯一解析入口 ToolContract.decode 使用独立严格 ObjectMapper，未知字段、类型强转及尾随 JSON 均拒绝。Session 只由可信演示入口/服务端认证结果构造，不能从工具 JSON 反序列化；调用方必须独立传入 PermissionGuard。当前无登录或工具执行入口，不能把 DTO 构造当作认证完成。

每次执行先校验输入，再调用守卫；user_id 来自 Session，enterprise_id 来自本次明确选择。守卫通过参数化 SQL 读取 permissions 并关联 users/enterprises，缺身份 AUTHENTICATION_REQUIRED，未知用户/企业、缺权限行、未知权限值均 PERMISSION_DENIED。U001 C001/C002 可读写；U002 C001 只读，C002 拒绝。不得从自然语言更改身份。未来事务写入须在同一事务内再次校验，T04 不提供写执行器。

任务/记忆归属固定为 (session.user_id, enterprise_id)，任务另有 task_id，记忆另有 memory_id；不能跨用户或企业恢复。每归属最多一个未完成任务，由 T08 存储实现。规则身份为 (rule_id, version)，适用企业由 rule_enterprises 指定。规则管理权限尚未确证：导入/更新/停用全部默认拒绝，包括 U001，不把企业写权限当规则管理员。

## 输入与输出

### 可检查的模型输入 Schema

规范文件为 `src/main/resources/tool-contract.schema.json`（JSON Schema draft 2020-12），通过 `ToolContract.schema()` 返回独立可检查的树。每个工具采用下表输入 DTO 同名 `$defs`；query_invoices/query_bank_entries 共用 Query，task/memory 共用 StateRequest。引用解析以整个规范文件为根。T07 可按供应商支持范围转换这些定义，不能放宽校验；本任务不提供供应商适配或执行循环。

所有 object（包括嵌套 key、changes、definition）均 `additionalProperties:false`；required 数组是必填字段，其余字段可省略。Query/PurposeCheck/InvoiceKey/RuleKey/RuleDefinition 字段全部必填且非 null；InvoiceUpdate 的 key/changes 必填，changes 至少一项，purpose/remarks 各自可选但必须 string。StateRequest/RuleRequest 的 enterprise_id/action 必填，id/content 或 key/definition 在 GET 可省略或 null；PUT、DELETE、IMPORT、UPDATE、DISABLE 的条件必填要求由 allOf/if/then 明确。字符串类型、月份 pattern、检查月份 const、枚举、日期 format 均见规范。日期 format 必须启用断言或由 DTO 的 LocalDate 严格解析。非空文本用非空白 pattern，不裁剪输入。

Schema 测试核对全部输入 record 的字段集合、required、嵌套禁止额外字段、枚举、条件约束及代表性输入；DTO 测试验证实际拒绝行为。Schema 描述有效输入，DTO 另提供稳定错误码（例如合法格式但超范围月份返回 UNSUPPORTED_SCOPE）。输出为下表不可变 Java record，JSON Schema 本轮覆盖模型可提交的输入参数，不含身份上下文。

JSON 字段使用 snake_case；必填字符串非空，未知字段拒绝。月份必须显式 yyyy-MM，禁止默认当前月；金额输出整数分，展示层转为两位小数元。查询不做发票/流水匹配。复合键不能省略 enterprise_id。

| 工具 | 输入 DTO / 字段 | 成功输出 |
|---|---|---|
| query_invoices | Query(enterprise_id, month) | InvoiceResult：rows、total_cents；每行 Invoice 含 key(enterprise_id, invoice_id)、invoice_date、seller、item、amount_cents、purpose、remarks |
| query_bank_entries | Query(enterprise_id, month) | BankResult：rows、income_cents、expense_cents；BankEntry 含 key(enterprise_id, entry_id)、transaction_date、direction(收入/支出)、amount_cents、counterparty、remarks |
| check_invoice_purpose | PurposeCheck(enterprise_id, month)，仅 2026-09 | PurposeResult：missing 发票复合键列表、rules 规则引用(name/id/version)列表；有效版本筛选和计算留 T06 |
| update_invoice | InvoiceUpdate(key, changes) | UpdateResult(key, purpose, remarks, verified)；verified 仅真实保存并回读一致后为 true |
| task | StateRequest(enterprise_id, action, id, content) | StateResult(id, content)；action=GET/PUT/DELETE，GET 的 id 可空表示当前未完成任务；其余必填；PUT content 必填，其他不得带 content |
| memory | 同 StateRequest | StateResult；GET id 空表示列出当前归属记忆，输出为列表；PUT 必须来自用户明确偏好/更正，DELETE 明确删除 |
| rules | RuleRequest(enterprise_id, action, key, definition) | GET 输出 RuleDefinition 列表；IMPORT/UPDATE/DISABLE 输出 RuleDefinition，但当前管理操作拒绝 |

RuleRequest action=GET/IMPORT/UPDATE/DISABLE；GET key 可空表示列表；其余 key 必填；IMPORT/UPDATE 必须携带 definition(name, effective_from, status, content)，其他不得携带；status=启用/已停用。未知管理权限不推定，跨版本冲突留 T06 明确。

changes 为非空 Map<string,string>，仅允许 purpose、remarks；省略表示保留，禁止 null、额外字段。空字符串/空白原样保留，不推定长度上限或擅自修剪。清空既有内容必须先取得用户明确清空要求。输入不含模型可伪造的 confirmed；后续 T09 必须使用服务端保存的用户明确授权（绑定用户/企业/发票/精确修改内容），未获授权返回 CONFIRMATION_REQUIRED；同时做幂等和回读，不能仅凭此 DTO 执行写入。

统一边界响应：成功 {data: <上述类型>, error: null}；失败 {data: null, error: {code, message}}。Response 约束二者恰有一个非空。查询成功无明细返回空 rows 与 0 分；权限或存储失败绝不能伪装空结果。此处定义响应类型，尚未暴露 HTTP API。

## 错误码

| code | 含义 |
|---|---|
| INVALID_ARGUMENT | 缺字段、未知字段、非法类型/月/枚举/组合 |
| AUTHENTICATION_REQUIRED | 无可信会话 |
| PERMISSION_DENIED | 用户/企业未知或无权限、只读拒写、规则管理未授权 |
| FIELD_NOT_ALLOWED | 发票更新含白名单外字段 |
| UNSUPPORTED_SCOPE | 用途检查月份不是 2026-09 |
| CONFIRMATION_REQUIRED | 无精确用户修改授权（T09） |
| NOT_FOUND | 已授权范围内目标不存在（后续执行器） |
| CONFLICT | 任务唯一性、版本或幂等冲突（后续执行器） |
| STORAGE_ERROR | SQLite 失败；对外不泄露 SQL/路径，原异常留 cause |

## 验收

### T09 安全写入 service（2026-10-30）

`InvoiceWriteService(Path)` 仅供可信后端调用，在指定既有库执行 `invoice-operations.sql` 增量建表，不导入业务记录。当前无启动注册、HTTP 或模型入口。可信入口核对真实用户指令后才调用 `authorizeExplicitRequest(session, operationId, update, intent)`：EXPLANATION 拒绝，SAVE_NOW 表示明确立即修改，CLEAR_NOW 还确认清空本次所有空白字段。禁止把此方法或 intent 暴露为模型工具；Authorization 构造器私有，不在 DTO 解码白名单。当前没有自然语言授权识别或生产认证链路，不能由模型自称已获授权。

一次性对象绑定用户、企业、票据、精确 changes 和 operationId（即 idempotencyKey），尝试执行即消费；同一对象不能再次执行。字段仅 purpose/remarks，省略保留，空白原样保存但须明确清空授权；不新增备注必填或长度限制，拒绝异常控制字符及空白标识符。不从票面项目猜用途，允许低于门槛主动补充。内部执行范围仅 2026-09，其他月份 UNSUPPORTED_SCOPE，不推断历史规则。

执行事务内复查 WRITE 权限、对象、当前启用规则，复用 T06 完整官方定义匹配；缺失/停用/未知正文或未知启用规则拒绝，多个用途版本 CONFLICT。SQLite 事务快照及写锁保证检查与写入一致，竞争失败不自动重试。`invoice_operations.operation_id` 全库唯一，台账绑定用户/企业/票据/规范化 payload 及实际 purpose/remarks，与更新原子提交。已有值相同只记操作、不 UPDATE；同键同内容仅查询台账和实际值，不重复更新；不同内容或归属 CONFLICT。后续实际值偏离台账也 CONFLICT，不覆盖后续修改。

更新后回读整张发票核验目标值及不可变字段，台账插入后再次回读，提交成功才返回 verified=true；任一步失败回滚。执行未知或调用方超时必须先 `recover(session, operationId, update)`，实时复检 WRITE 权限、查询台账和实际票据；匹配返回 Optional 中 verified=true，缺记录 empty，差异 CONFLICT，查询失败 STORAGE_ERROR。empty 仅说明未观察到已提交操作，不能盲目重试；在途操作需等待结束再查询，新尝试须由可信入口重新明确授权。跨进程恢复依赖持久台账，无自动写入重试。

### T06 本地 service 层补充（2026-10-30）

`RuleService` 未注册 HTTP 或模型工具入口，T04 输入 DTO/Schema 不变。`queryRules(session, enterprise, date)` 输出 `StoredRule(key, definition)` 不可变列表，含完整 ID/版本/名称/正文；date=null 包含停用历史，指定日期只返回企业关联、启用且生效日不晚于该日的规则。`checkInvoicePurpose` 输出既有 PurposeResult，在同一只读事务读取权限、规则和当前发票。规则正文可通过 queryRules 检索。

确定性用途执行仅支持官方 R002 2.0 / R003 1.0，完整定义必须匹配已核对种子中的语义基线；当前状态与关联以 DB 为准，停用绝不回退。缺失/未知正文或版本为 UNSUPPORTED_SCOPE，同一用途规则多个启用版本适用为 CONFLICT，不推定官方未定义的版本优先级。用途月份仍仅 2026-09。

普通 Session 的管理全部拒绝（包括 U001），PermissionGuard 保持不变。仅可信本地代码显式创建 ManagementContext 可调用 importVersion/disableVersion；不得从模型、HTTP 或自然语言构造此上下文，当前无入口签发它。它是本地管理能力，不代表官方已定义管理员身份。导入事务原子保存规则及非空企业集合，未知企业/非法定义 INVALID_ARGUMENT、已存在版本 CONFLICT；更新使用新版本，保留旧正文。停用针对全局版本及其所有企业关联，保留正文与历史记录；目标缺失 NOT_FOUND。SQL 失败回滚并返回 STORAGE_ERROR。无数据库迁移。

ToolContractTest 与 PermissionGuardTest 覆盖矩阵、未知主体、缺权限、SQL 失败、字段白名单、严格 JSON、非法查询和不可变快照。执行 README 的 JDK21 `mvn -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test`；实际结果记录 TASKS.md。仅测试通过且审查通过后标 T04 DONE。
