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
export interface TurnView { turn: {task: Task; result: Result} | null; verification: Verification | null; errorCode: string | null; message: string; operationId: string | null }
export interface Approval { operationId: string; invoiceId: string; changes: Partial<Record<'purpose' | 'remarks', string>>; confirmed: true }
export interface AuditEvent { id: number; requestId: string; taskId: string | null; tool: string | null; callId: string | null; month: string | null; invoiceId: string | null; fields: string | null; operationId: string | null; outcome: string; errorCode: string | null; verified: boolean; time: string }
export interface Invoice { key: {enterprise_id: string; invoice_id: string}; invoice_date: string; seller: string; item: string; amount_cents: number; purpose: string; remarks: string }

/** Fail closed after an approved financial turn: a missing tool history is never proof of no write. */
export function verifiedWriteResult(result: TurnView, operationId: string): boolean {
  return result.operationId === operationId && result.verification?.verified === true
}

export class ApiError extends Error {
  constructor(public code: string, public status = 0) { super(friendlyError(code)); }
}
export function friendlyError(code: string) {
  const messages: Record<string, string> = {
    MODEL_NOT_CONFIGURED: '真实模型未配置。请在服务端设置 LLM_ENDPOINT、LLM_API_KEY 和 LLM_MODEL；当前没有模拟回复。',
    CONFLICT: '任务版本或进行中的任务存在冲突，请重新加载任务核对。',
    PERMISSION_DENIED: '当前身份没有该企业或操作权限。',
    AUTHENTICATION_REQUIRED: '请先选择本地身份与企业。',
    CSRF_REQUIRED: '会话校验已失效，请重新连接或选择身份。',
    MODEL_TIMEOUT: '模型响应超时，请先核查实际工具与审计状态。',
    AUDIT_STORAGE_ERROR: '审计存储失败，不能声明整体成功；请核查数据库回读。',
    TASK_STATE_SAVE_FAILED: '财务结果已返回，但任务状态保存失败；请核查回读，勿重复写入。',
    NETWORK_UNKNOWN: '未收到可确认的响应。结果未知，请核查审计和数据库，勿重复保存。',
    INVALID_RESPONSE: '服务端返回无法识别的响应，请核查服务与实际执行状态。',
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
  audit() { return this.request<AuditEvent[]>('/audit') }
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
