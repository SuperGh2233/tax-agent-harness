// Mirrors DemoController, TaskMemory, LlmClient and ToolContract. Tool JSON uses snake_case.
export interface Session { csrfToken: string; profile: string; enterprise: string; identityNotice: string }
export interface Statement { value: string; userRequest: string }
export interface Task { id: string; status: 'ACTIVE' | 'WAITING_USER' | 'COMPLETED' | 'CANCELLED'; version: number; goal: string; pending: string[]; supplied: Record<string, Statement>; question: string | null; recentTools: {tool: string; outcome: string}[] }

/** Terminal tasks are historical context only; create a new task to continue work. */
export function isTerminalTaskStatus(status: Task['status']): boolean {
  return status === 'COMPLETED' || status === 'CANCELLED'
}
/** A database index permits only one ACTIVE/WAITING_USER task per user and enterprise. */
export function findOpenTask(tasks: Task[]): Task | undefined {
  return tasks.find(task => task.status === 'ACTIVE' || task.status === 'WAITING_USER')
}
export interface Context { task: Task; preferences: Record<string, Statement> }
export interface Exchange { call: {id: string; name: string; arguments: string}; resultJson: string }
export interface Result { status: 'FINISHED' | 'NEEDS_INPUT' | 'ERROR'; text: string; errorCode: string | null; history: Exchange[] }
export interface Verification { key: {enterpriseId: string; invoiceId: string}; purpose: string; remarks: string; verified: boolean }
export interface PromptTrace { systemPrompt: string; userMessage: string; tools: {name: string; parameters: unknown}[]; historySize: number }
export interface TurnView { turn: {task: Task; result: Result} | null; verification: Verification | null; errorCode: string | null; message: string; operationId: string | null; modelInput?: string | null; promptTrace?: PromptTrace | null }
export interface Approval { operationId: string; invoiceId: string; changes: Partial<Record<'purpose' | 'remarks', string>>; confirmed: true }
export interface OperationVerification { operationId: string; status: 'VERIFIED' | 'NOT_FOUND' | 'DIVERGED'; verification: Verification | null }
export function canVerifyOperation(operation: {id: string; taskId: string} | null): boolean {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
  return !!operation && uuid.test(operation.id) && uuid.test(operation.taskId)
}
/** Shared by the React manual action and tests; clearing storage must succeed before unlocking. */
export async function verifyPendingOperation(api: Pick<DemoApi, 'verify'>, operation: {id: string; taskId: string}, clear: () => void) {
  if (!canVerifyOperation(operation)) throw new ApiError('INVALID_RESPONSE')
  const result = await api.verify(operation.taskId, operation.id)
  if (result?.operationId !== operation.id || result?.status !== 'VERIFIED' || result?.verification?.verified !== true)
    throw new Error('写入仍未核验；保留锁定，请人工核查。NOT_FOUND 不表示可以重试。')
  clear()
}
export interface AuditEvent { id: number; requestId: string; taskId: string | null; tool: string | null; callId: string | null; month: string | null; invoiceId: string | null; fields: string | null; operationId: string | null; outcome: string; errorCode: string | null; verified: boolean; time: string }
export interface SavedTrace { id: number; requestId: string; taskId: string; systemPrompt: string; userMessage: string; toolNames: string[]; historySize: number; memorySource: string | null; outcome: string | null; time: string }
export interface Invoice { key: {enterprise_id: string; invoice_id: string}; invoice_date: string; seller: string; item: string; amount_cents: number; purpose: string; remarks: string }

/** Fail closed after an approved financial turn: a missing tool history is never proof of no write. */
export function verifiedWriteResult(result: TurnView, operationId: string): boolean {
  return result.operationId === operationId && result.verification?.verified === true
}

/** A trusted terminal receipt: this approved turn ended without dispatching a write. */
export function provenNotExecuted(result: TurnView, operationId: string): boolean {
  return result.operationId === operationId
    && result.errorCode === 'WRITE_NOT_EXECUTED'
    && result.verification === null
}

export class ApiError extends Error {
  constructor(public code: string, public status = 0) { super(friendlyError(code)); }
}
export type PlanState = 'done' | 'current' | 'waiting' | 'optional'
export interface PlanStep { key: string; title: string; state: PlanState; detail: string }
export interface RuleCitation { id: string; version: string; name?: string }

const QUERY_TOOLS = new Set(['query_invoices', 'query_bank_entries', 'check_invoice_purpose', 'query_rules'])

function hasVerifiedUpdate(history: Exchange[]) {
  return history.some(exchange => {
    if (exchange.call.name !== 'update_invoice') return false
    try { return JSON.parse(exchange.resultJson)?.data?.verified === true } catch { return false }
  })
}

/** Deterministic plan derived from backend task state and tool history, never from model prose. */
export function deriveExecutionPlan(task: Task | null, history: Exchange[], verification: Verification | null): PlanStep[] {
  if (!task) return []
  const toolNames = new Set([...history.map(exchange => exchange.call.name), ...task.recentTools.map(tool => tool.tool)])
  const queried = [...QUERY_TOOLS].some(name => toolNames.has(name))
  const pending = task.pending.length > 0
  const completed = task.status === 'COMPLETED'
  const waiting = task.status === 'WAITING_USER'
  const verified = verification?.verified === true || hasVerifiedUpdate(history)
  const writeAttempted = history.some(exchange => exchange.call.name === 'update_invoice')
  return [
    {key:'scope', title:'确认企业与任务范围', state:'done', detail:task.goal},
    {key:'facts', title:'查询当前事实', state:queried ? 'done' : (completed || waiting) ? 'done' : 'current',
      detail:queried ? '已调用发票、银行或规则工具' : '尚未查询当前数据库'},
    {key:'rules', title:'核对规则与用途缺口', state:queried ? 'done' : 'current',
      detail:queried ? '以工具结果为准，不采用模型心算' : '等待查询规则/用途'},
    {key:'input', title:'补充或更正明确事实', state:pending ? 'waiting' : 'done',
      detail:pending ? `待补：${task.pending.join('、')}` : '没有待补字段'},
    {key:'write', title:'保存并回读核验', state:verified ? 'done' : pending ? 'optional' : writeAttempted ? 'current' : 'optional',
      detail:verified ? 'verified=true，数据库回读一致' : writeAttempted ? '已发起写入但未核验，禁止重复保存' : '只读、尚未授权保存'},
    {key:'close', title:'结项证据门', state:completed ? 'done' : waiting ? 'waiting' : 'current',
      detail:completed ? '任务完成且证据门通过' : waiting ? '等待用户补充，不能提前结项' : '执行中，禁止用模型文字结项'}
  ]
}

/** Rules actually returned by query_rules in this turn; model citations are never evidence by themselves. */
export function citedRules(history: Exchange[]): RuleCitation[] {
  const found = new Map<string, RuleCitation>()
  for (const exchange of history) {
    if (exchange.call.name !== 'query_rules') continue
    try {
      const rules = JSON.parse(exchange.resultJson)?.data?.rules
      if (!Array.isArray(rules)) continue
      for (const rule of rules) {
        const id = rule?.key?.rule_id
        const version = rule?.key?.version
        if (typeof id === 'string' && typeof version === 'string') {
          found.set(`${id}@${version}`, {id, version, name:typeof rule.name === 'string' ? rule.name : undefined})
        }
      }
    } catch { /* malformed historical result is not a citation */ }
  }
  return [...found.values()]
}

export function friendlyError(code: string) {
  const messages: Record<string, string> = {
    MODEL_NOT_CONFIGURED: '真实模型未配置。请在服务端设置 LLM_ENDPOINT、LLM_API_KEY 和 LLM_MODEL；当前没有模拟回复。',
    MODEL_FAILURE: '模型没有给出可执行的结论；本轮没有据此执行保存。请重试，或查看工具与审计记录确认已发生的只读操作。',
    MODEL_TIMEOUT: '模型响应超时；本轮不能据此声明成功。请以工具和审计记录为准，勿重复写入。',
    CONFLICT: '任务版本或进行中的任务存在冲突，请重新加载任务核对。',
    CONFIRMATION_REQUIRED: '保存需要本轮明确确认；当前没有获得授权。',
    PERMISSION_DENIED: '当前身份没有该企业或操作权限。',
    AUTHENTICATION_REQUIRED: '请先选择本地身份与企业。',
    CSRF_REQUIRED: '会话校验已失效，请重新连接或选择身份。',
    INVALID_ARGUMENT: '请求或工具参数不完整；请补充企业、月份和明确值。',
    TOOL_FAILURE: '工具执行失败，结果不能作为业务结论；请核查参数或权限后重试只读查询。',
    TOOL_TIMEOUT: '工具执行超时，结果未知；请先只读核验，不要重复写入。',
    MAX_STEPS: '本轮工具步骤过多，系统已停止；请缩小查询范围后重试，不要重复写入。',
    RUNTIME_FAILURE: '执行过程中出现未分类异常；请以审计和数据库回读为准，不要重复保存。',
    INTERRUPTED: '请求被中断，结果未知；请核查审计与数据库回读。',
    AUDIT_STORAGE_ERROR: '审计存储失败，不能声明整体成功；请核查数据库回读。',
    TASK_STATE_SAVE_FAILED: '财务结果已返回，但任务状态保存失败；请核查回读，勿重复写入。',
    NETWORK_UNKNOWN: '未收到可确认的响应。结果未知，请核查审计和数据库，勿重复保存。',
    INVALID_RESPONSE: '服务端返回无法识别的响应，请核查服务与实际执行状态。',
    WRITE_NOT_EXECUTED: '本轮未执行保存；旧批准已结束，如需保存请重新确认。',
    WRITE_NOT_VERIFIED: '写入结果未核验；保留操作锁，请核查台账与发票。',
  }
  return `${messages[code] ?? '请求未完成，请核查服务、权限与实际执行结果。'} (${code})`
}

// Small REST runtime boundary: no streaming simulation, retries or persistent approval.
export class DemoApi {
  private csrf = ''
  private async request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    let response: Response
    try {
      response = await fetch(`/api/demo${path}`, {method, credentials: 'same-origin', cache: 'no-store',
        headers: {'Content-Type': 'application/json', 'X-CSRF-Token': this.csrf},
        body: body === undefined ? undefined : JSON.stringify(body)})
    } catch { throw new ApiError('NETWORK_UNKNOWN') }
    let data: T & {errorCode?: string}
    try { data = await response.json() } catch { throw new ApiError('INVALID_RESPONSE', response.status) }
    if (!response.ok) throw new ApiError(data.errorCode ?? `HTTP_${response.status}`, response.status)
    return data
  }
  async session() { const s = await this.request<Session>('/session'); this.csrf = s.csrfToken; return s }
  async select(profile: string, enterprise: string) { const s = await this.request<Session>('/session', 'POST', {profile, enterprise}); this.csrf = s.csrfToken; return s }
  async logout() { await this.request('/session', 'DELETE'); return this.session() }
  tasks() { return this.request<Task[]>('/tasks') }
  start(goal: string, pending: string[]) { return this.request<Task>('/tasks', 'POST', {goal, pending}) }
  /** Explicit user action only. Cancelling a task never modifies or rolls back financial records. */
  cancel(id: string, version: number) { return this.request<Task>(`/tasks/${encodeURIComponent(id)}/cancel`, 'POST', {version, confirmed: true}) }
  load(id: string) { return this.request<Context>(`/tasks/${encodeURIComponent(id)}`) }
  supplement(id: string, version: number, statements: Record<string, Statement>) { return this.request<Task>(`/tasks/${encodeURIComponent(id)}/supplement`, 'POST', {version, statements}) }
  turn(id: string, message: string, approval?: Approval) { return this.request<TurnView>(`/tasks/${encodeURIComponent(id)}/turn`, 'POST', {message, ...(approval ? {approval} : {})}) }
  preferences() { return this.request<Record<string, Statement>>('/preferences') }
  putPreference(name: string, value: string, userRequest: string) { return this.request<Record<string, Statement>>('/preferences', 'POST', {name, value, userRequest}) }
  deletePreference(name: string) { return this.request<Record<string, Statement>>(`/preferences/${encodeURIComponent(name)}`, 'DELETE') }
  verify(taskId: string, operationId: string) { return this.request<OperationVerification>(`/tasks/${encodeURIComponent(taskId)}/operations/${encodeURIComponent(operationId)}/verify`, 'POST') }
  audit() { return this.request<AuditEvent[]>('/audit') }
  /** Persisted model inputs; survives reload and restart, newest first. */
  traces(id: string) { return this.request<SavedTrace[]>(`/tasks/${encodeURIComponent(id)}/traces`) }
}

/** Creating a task and running its first turn are separate backend operations.
 * The callback publishes the created task immediately, even if the model call fails.
 * A failed first turn is never retried automatically. */
export async function createAndRunFirstTurn(
  api: Pick<DemoApi, 'start' | 'turn'>,
  goal: string,
  pending: string[],
  onCreated: (task: Task) => void,
): Promise<{created: Task; response: TurnView | null; executionError: Error | null}> {
  const created = await api.start(goal, pending)
  onCreated(created)
  try {
    const response = await api.turn(created.id, goal)
    return {created, response, executionError: null}
  } catch (error) {
    return {created, response: null, executionError: error instanceof Error ? error : new Error('首轮执行结果未知')}
  }
}

export function parseTool(exchange: Exchange): {data: unknown; error: {code: string} | null} | null {
  try {
    const r = JSON.parse(exchange.resultJson)
    if (typeof r !== 'object' || r === null || !('data' in r) || !('error' in r)) return null
    return r
  } catch { return null }
}
function isInvoice(row: unknown): row is Invoice {
  if (!row || typeof row !== 'object') return false
  const r = row as Record<string, unknown>, key = r.key as Record<string, unknown> | undefined
  return !!key && typeof key.enterprise_id === 'string' && typeof key.invoice_id === 'string'
    && ['invoice_date','seller','item','purpose','remarks'].every(k => typeof r[k] === 'string')
    && Number.isSafeInteger(r.amount_cents)
}
export function queriedInvoices(history: Exchange[], enterprise: string): Invoice[] {
  // Only the latest successful invoice query in the latest returned turn is current evidence.
  for (const exchange of [...history].reverse()) {
    if (exchange.call.name === 'update_invoice') return [] // A later write invalidates earlier DB values.
    if (exchange.call.name !== 'query_invoices') continue
    const result = parseTool(exchange)
    if (!result || result.error || !result.data || typeof result.data !== 'object') return []
    const rows = (result.data as {rows?: unknown}).rows
    if (!Array.isArray(rows) || !rows.every(isInvoice)) return []
    return rows.filter(r => r.key.enterprise_id === enterprise)
  }
  return []
}
