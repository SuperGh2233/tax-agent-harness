// Mirrors DemoController, TaskMemory, LlmClient and ToolContract. Tool JSON uses snake_case.
export interface Session { csrfToken: string; profile: string; enterprise: string; identityNotice: string }
export interface Statement { value: string; userRequest: string }
export interface Task { id: string; status: 'ACTIVE' | 'WAITING_USER' | 'COMPLETED' | 'CANCELLED'; version: number; goal: string; pending: string[]; supplied: Record<string, Statement>; question: string | null; recentTools: {tool: string; outcome: string}[]; plan?: {steps: PlanStep[]} | null; awaitingSave?: string[] }

/** Terminal tasks are historical context only; create a new task to continue work. */
export function isTerminalTaskStatus(status: Task['status']): boolean {
  return status === 'COMPLETED' || status === 'CANCELLED'
}
/** A database index permits only one ACTIVE/WAITING_USER task per user and enterprise. */
export function findOpenTask(tasks: Task[]): Task | undefined {
  return tasks.find(task => task.status === 'ACTIVE' || task.status === 'WAITING_USER')
}
// Mirrors TaskMemory (T33). The most specific scope wins for one name: USER_ENTERPRISE, then PERSONAL, then ENTERPRISE.
export type MemoryScope = 'USER_ENTERPRISE' | 'PERSONAL' | 'ENTERPRISE'
export interface Memory { scope: MemoryScope; name: string; value: string; userRequest: string; expiresOn: string | null; updatedAt: string | null; effective: boolean }
export const SCOPE_LABEL: Record<MemoryScope, string> = {USER_ENTERPRISE: '仅我 · 当前企业', PERSONAL: '个人 · 所有企业', ENTERPRISE: '企业共享'}
/** Client copy of the server checks, so the form explains a refusal before sending. */
export function memoryProblem(scope: MemoryScope, name: string, value: string, userRequest: string, expiresOn: string, today: string): string | null {
  if (!name.trim() || !value.trim() || !userRequest.trim()) return '名称、明确值和你的原话都必须填写。'
  if (name.includes('·')) return '名称不能包含“·”。'
  if (scope === 'PERSONAL' && /(^|[^A-Za-z0-9])[CFTcft][0-9]{3}(?![0-9])/.test(name + ' ' + value)) return '个人记忆跨企业使用，不能包含企业、发票或流水编号；请改为“仅我 · 当前企业”。'
  if (expiresOn && expiresOn < today) return '有效期不能早于今天。'
  return null
}
export interface Context { task: Task; preferences: Record<string, Statement>; agent?: AgentDef | null }
// Mirrors AgentDirectory (T30). A turn uses the bound agent's version in force when the turn starts.
export const AGENT_TOOLS = ['query_invoices', 'query_bank_entries', 'check_invoice_purpose', 'query_rules', 'search_knowledge', 'recall_context', 'update_invoice'] as const
export interface AgentSpec { name: string; responsibility: string; instructions: string; modelProfile: string; model: string | null; tools: string[]; enterprises: string[]; skills?: string[] }
export interface AgentDef { id: string; version: number; spec: AgentSpec; enabled: boolean; changedBy: string; changedAt: string }
export interface ModelProfile { name: string; configured: boolean; model: string | null }
export interface Effective { tools: string[]; disabledTools: string[]; skills: Skill[]; droppedSkills: string[] }
export interface AgentsView { agents: AgentDef[]; effective?: Record<string, Effective>; profiles: ModelProfile[]; tools: string[] }
// Mirrors CapabilityRegistry (T31).
export interface ToolInfo { name: string; version: string; description: string; enabled: boolean; changedBy: string | null; changedAt: string | null; usedBy: string[] }
export interface SkillSpec { name: string; description: string; instructions: string; tools: string[] }
export interface Skill { id: string; version: number; spec: SkillSpec; enabled: boolean; changedBy: string; changedAt: string }
export interface SkillInfo { skill: Skill; usedBy: string[] }
export interface Capabilities { tools: ToolInfo[]; skills: SkillInfo[] }
/** Shown before a global switch: who is affected, in plain words. */
export function impactMessage(kind: '工具' | '技能', name: string, usedBy: string[], enabling: boolean): string {
  const who = usedBy.length ? `将影响 ${usedBy.length} 个 Agent：${usedBy.join('、')}` : '当前没有 Agent 使用它'
  return enabling ? `启用${kind} ${name}：${who}，从下一轮对话起可用。`
    : `停用${kind} ${name}：${who}。从下一轮对话起不再提供给模型，调用会返回 CAPABILITY_DISABLED；进行中的一轮不受影响。`
}
export function skillProblem(id: string, spec: SkillSpec): string | null {
  if (!/^[a-z][a-z0-9-]{2,39}$/.test(id)) return '技能编号需 3–40 位小写字母、数字或连字符，并以字母开头。'
  if (!spec.name.trim() || !spec.instructions.trim()) return '名称和说明必须填写。'
  if (spec.instructions.length > 2000) return '说明不能超过 2000 字。'
  if (!spec.tools.length) return '至少选择一个工具。'
  if (spec.tools.includes('update_invoice')) return '技能不能授予保存能力；保存只能直接授予 Agent。'
  return null
}
/** Agents a new task in this enterprise may use; the server re-checks when the task starts and on every turn. */
export function usableAgents(agents: AgentDef[], enterprise: string): AgentDef[] {
  return agents.filter(agent => agent.enabled && agent.spec.enterprises.includes(enterprise))
}
/** Client-side check of the same rules AgentDirectory enforces, so the form explains a refusal before sending. */
export function agentProblem(id: string, spec: AgentSpec): string | null {
  if (!/^[a-z][a-z0-9-]{2,39}$/.test(id)) return 'Agent 编号需 3–40 位小写字母、数字或连字符，并以字母开头。'
  if (!spec.name.trim() || !spec.responsibility.trim()) return '名称和职责必须填写。'
  if (spec.instructions.length > 4000) return '提示词不能超过 4000 字。'
  if (!spec.tools.length && !(spec.skills ?? []).length) return '至少选择一个能力或技能。'
  if (spec.tools.length === 1 && spec.tools[0] === 'update_invoice' && !(spec.skills ?? []).length) return '只有保存能力的 Agent 无法回读核验，请同时选择查询能力。'
  if (!spec.enterprises.length) return '至少选择一家适用企业。'
  return null
}
export interface Exchange { call: {id: string; name: string; arguments: string}; resultJson: string }
export interface Result { status: 'FINISHED' | 'NEEDS_INPUT' | 'ERROR'; text: string; errorCode: string | null; history: Exchange[] }
export interface Verification { key: {enterpriseId: string; invoiceId: string}; purpose: string; remarks: string; verified: boolean }
export interface PromptTrace { systemPrompt: string; userMessage: string; tools: {name: string; parameters: unknown}[]; historySize: number }
// T47: an approved batch's verified read-back plus the program's review list. Envelope fields are camelCase.
export interface ReviewItem { key: {enterpriseId: string; invoiceId: string}; reasons: string[] }
export interface BatchResult { results: Verification[]; verified: boolean; review: ReviewItem[]; note: string }
export interface TurnView { turn: {task: Task; result: Result} | null; verification: Verification | null; errorCode: string | null; message: string; operationId: string | null; modelInput?: string | null; promptTrace?: PromptTrace | null; batch?: BatchResult | null }
export interface ToolProgress { phase: 'tool_start' | 'tool_result'; callId: string; name: string; durationMs: number; outcome: string }
export type Changes = Partial<Record<'purpose' | 'remarks', string>>
/** A single save sets invoiceId and changes; a batch (T47) sets items, each with its own operationId. */
export interface BatchEntry { operationId: string; invoiceId: string; changes: Changes }
export interface Approval { operationId: string; invoiceId?: string; changes?: Changes; items?: BatchEntry[]; confirmed: true }
export const MAX_BATCH = 20
export const REVIEW_REASON: Record<string, string> = {
  OVERWROTE_PURPOSE: '覆盖了原有用途', OVERWROTE_REMARKS: '覆盖了原有备注',
  CLEARED_PURPOSE: '用途被清空', CLEARED_REMARKS: '备注被清空',
  PURPOSE_STILL_REQUIRED: '按当前规则仍须填写用途', RULE_NOT_CHECKABLE: '当前规则无法判断，需人工核对',
}
/** T47: only a batch whose every invoice came back verified, in the approved order and count, releases the lock. */
export function verifiedBatchResult(result: TurnView, operationId: string, items: string[]): boolean {
  const batch = result.batch
  return result.operationId === operationId && !!batch && batch.verified === true
    && Array.isArray(batch.results) && batch.results.length === items.length && batch.results.every(r => r?.verified === true)
}
export interface OperationVerification { operationId: string; status: 'VERIFIED' | 'NOT_FOUND' | 'DIVERGED'; verification: Verification | null }
export function canVerifyOperation(operation: {id: string; taskId: string} | null): boolean {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
  return !!operation && uuid.test(operation.id) && uuid.test(operation.taskId)
}
/** Shared by the React manual action and tests; clearing storage must succeed before unlocking. */
export async function verifyPendingOperation(api: Pick<DemoApi, 'verify'>, operation: {id: string; taskId: string; items?: string[]}, clear: () => void) {
  if (!canVerifyOperation(operation)) throw new ApiError('INVALID_RESPONSE')
  // T47: a batch is verified per invoice; the lock stays unless every one of them is VERIFIED.
  const ids = operation.items?.length ? operation.items : [operation.id]
  if (operation.items?.length && !operation.items.every(id => canVerifyOperation({id, taskId: operation.taskId}))) throw new ApiError('INVALID_RESPONSE')
  for (const id of ids) {
    const result = await api.verify(operation.taskId, id)
    if (result?.operationId !== id || result?.status !== 'VERIFIED' || result?.verification?.verified !== true)
      throw new Error('写入仍未核验；保留锁定，请人工核查。NOT_FOUND 不表示可以重试。')
  }
  clear()
}
export interface AuditEvent { id: number; requestId: string; taskId: string | null; tool: string | null; callId: string | null; month: string | null; invoiceId: string | null; fields: string | null; operationId: string | null; outcome: string; errorCode: string | null; verified: boolean; time: string }
export interface SavedTrace { id: number; requestId: string; taskId: string; systemPrompt: string; userMessage: string; toolNames: string[]; historySize: number; memorySource: string | null; outcome: string | null; time: string }
export interface Invoice { key: {enterprise_id: string; invoice_id: string}; invoice_date: string; seller: string; item: string; amount_cents: number; purpose: string; remarks: string }
// Mirrors FileService (T29). Envelope fields are camelCase.
export interface StoredFile { id: string; enterpriseId: string; name: string; version: number; sha256: string; kind: 'XLSX' | 'XLS' | 'DOCX' | 'PDF'; sizeBytes: number; uploadedBy: string; uploadedAt: string; knowledgeEnabled: boolean; latest: boolean }
export interface RowIssue { sheet: string | null; row: number; recordId: string | null; reason: string }
// T46: a sheet that looks like a table but was not imported; suggested is a hint, never applied without the accountant.
export interface TableCandidate { sheet: string; headerRow: number; headers: string[]; samples: string[][]; suggestedKind: 'INVOICE' | 'BANK'; suggested: Record<string, number>; reason: 'UNRECOGNIZED_HEADER' | 'MISSING_COLUMNS' }
export interface SheetMapping { sheet: string; kind: 'INVOICE' | 'BANK'; headerRow: number; columns: Record<string, number> }
export interface UploadResult { status: 'IMPORTED' | 'DUPLICATE' | 'REJECTED'; file: StoredFile | null; invoicesImported: number; bankEntriesImported: number; conflicts: RowIssue[]; skipped: RowIssue[]; errors: RowIssue[]; chunks: number; tables?: TableCandidate[] }
export const MAPPING_FIELDS: Record<'INVOICE' | 'BANK', {field: string; label: string; required: boolean}[]> = {
  INVOICE: [{field:'ID',label:'发票编号',required:true},{field:'DATE',label:'开票日期',required:true},{field:'SELLER',label:'销售方名称',required:true},
    {field:'ITEM',label:'项目名称',required:true},{field:'AMOUNT',label:'价税合计（元）',required:true},{field:'PURPOSE',label:'用途',required:false},
    {field:'REMARKS',label:'备注',required:false},{field:'ENTERPRISE',label:'企业编号',required:false}],
  BANK: [{field:'ID',label:'流水编号',required:true},{field:'DATE',label:'交易日期',required:true},{field:'DIRECTION',label:'收支方向',required:true},
    {field:'AMOUNT',label:'金额（元）',required:true},{field:'COUNTERPARTY',label:'对方名称',required:true},{field:'REMARKS',label:'备注',required:false},
    {field:'ENTERPRISE',label:'企业编号',required:false}],
}
/** Client copy of the server's mapping checks: every required field once, no column used twice. */
export function mappingProblem(kind: 'INVOICE' | 'BANK', columns: Record<string, number>): string | null {
  const missing = MAPPING_FIELDS[kind].filter(f => f.required && columns[f.field] === undefined).map(f => f.label)
  if (missing.length) return `还需要指定：${missing.join('、')}。`
  const used = Object.values(columns)
  if (new Set(used).size !== used.length) return '同一列不能对应两个字段。'
  return null
}
// T48: scanned invoices. A model reading is only a draft; the invoice exists after the accountant confirms it.
export interface ScanDraft { status: 'DRAFT' | 'MANUAL'; errorCode: string | null; draftId: string; file: StoredFile; fields: Record<string, string>; unreadable: string[]; checks: string[]; pageImage: string }
export interface ScanConfirmation { invoiceId: string; invoiceDate: string; seller: string; item: string; amount: string; remarks: string; amountConfirmation: string; confirmed: true }
export interface ScanImport { status: 'IMPORTED' | 'CONFLICT'; file: StoredFile; invoiceId: string; amountCents: number; differences: string[] }
export const SCAN_CHECK: Record<string, string> = {
  AMOUNT_WORDS_MATCH: '大写金额与小写金额一致', AMOUNT_WORDS_MISMATCH: '大写金额与小写金额不一致，请对照票面核实',
  AMOUNT_WORDS_UNREADABLE: '未能读出大写金额，请对照票面核实金额', AMOUNT_INVALID: '识别出的金额不是有效数字',
  DATE_INVALID: '识别出的日期无法确认', EXISTING_INVOICE: '系统中已有同编号发票，确认后不会覆盖',
}
/** "1,234.5" → 123450; NaN unless a plain amount with at most two decimals (the server applies the same rule). */
export function amountCents(value: string): number {
  const text = value.trim().replace(/,/g, '')
  return /^\d+(\.\d{1,2})?$/.test(text) ? Math.round(Number(text) * 100) : NaN
}
export function scanProblem(c: Omit<ScanConfirmation, 'confirmed'>): string | null {
  if (![c.invoiceId, c.invoiceDate, c.seller, c.item, c.amount].every(v => v.trim())) return '发票编号、开票日期、销售方、项目和价税合计都必须填写。'
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(c.invoiceId.trim())) return '发票编号只能包含字母、数字、- 和 _。'
  if (!/^\d{4}-\d{2}-\d{2}$/.test(c.invoiceDate.trim())) return '开票日期请写成 YYYY-MM-DD。'
  if (!(amountCents(c.amount) > 0)) return '价税合计须为正数，最多两位小数。'
  if (amountCents(c.amount) !== amountCents(c.amountConfirmation)) return '两次输入的价税合计不一致。'
  return null
}
export interface FileChunk { seq: number; location: string; text: string }
export interface FileDetail { file: StoredFile; chunks: FileChunk[] }

/** Same limits as FileService; the server re-checks everything, this only spares a doomed upload. */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024
export const UPLOAD_ACCEPT = '.xlsx,.xls,.docx,.pdf'
export function uploadProblem(file: {name: string; size: number}): string | null {
  if (!/\.(xlsx|xls|docx|pdf)$/i.test(file.name)) return '只支持 Excel（.xlsx/.xls）、Word（.docx）和文字型 PDF。'
  if (file.size === 0) return '文件是空的。'
  if (file.size > MAX_UPLOAD_BYTES) return '文件超过 10 MB 上限。'
  return null
}
const issueReasons: Record<string, string> = {
  EXISTING_RECORD: '系统中已有同编号记录，未覆盖',
  ENTERPRISE_MISMATCH: '属于其他企业，未导入',
  INVALID_AMOUNT: '金额无效（须为数字且最多两位小数）',
  INVALID_DATE: '日期无法识别',
  INVALID_DIRECTION: '收支方向须为“收入”或“支出”',
  MISSING_REQUIRED_FIELD: '缺少必填字段',
  MISSING_ENTERPRISE: '缺少企业编号',
  DUPLICATE_IN_FILE: '文件内编号重复',
  NOT_TEXT_PDF: '不是文字型 PDF（扫描件无法读取文字；发票扫描件可用下方“识别扫描件发票”）',
  MAPPED_SHEET_NOT_FOUND: '指定列对应的工作表不存在',
  ENCRYPTED_PDF: 'PDF 已加密',
  NO_TEXT: '文档没有可读取的文字',
  TOO_MANY_SEGMENTS: '内容过多，超出分段上限',
}
export function issueReason(reason: string): string {
  if (reason.startsWith('MISSING_COLUMNS:')) return `表头缺少必需列 ${reason.slice('MISSING_COLUMNS:'.length)}`
  return issueReasons[reason] ?? reason
}
export function issueLocation(issue: RowIssue): string {
  return [issue.sheet, issue.row > 0 ? `第${issue.row}行` : null, issue.recordId].filter(Boolean).join(' · ') || '整个文件'
}
/** One plain sentence about what the server actually stored; never claims more than the result says. */
export function describeUpload(result: UploadResult): string {
  if (result.status === 'REJECTED') return `文件未导入：发现 ${result.errors.length} 处问题，系统没有保存任何内容。`
  if (result.status === 'DUPLICATE') return `相同内容已在 ${result.file?.name ?? '此前的文件'}（第 ${result.file?.version ?? '?'} 版）上传过，本次没有新增任何数据。`
  const parts = [`已保存为 ${result.file?.name} 第 ${result.file?.version} 版`]
  if (result.invoicesImported || result.bankEntriesImported) parts.push(`新增发票 ${result.invoicesImported} 张、流水 ${result.bankEntriesImported} 条`)
  else parts.push('没有新增发票或流水')
  if (result.conflicts.length) parts.push(`${result.conflicts.length} 条与已有记录同编号，未覆盖`)
  if (result.skipped.length) parts.push(`${result.skipped.length} 条属于其他企业，未导入`)
  parts.push(`共 ${result.chunks} 个可引用文本段`)
  if (result.tables?.length) parts.push(`另有 ${result.tables.length} 个表格未能识别列，可在下方指定列对应后导入`)
  return parts.join('；') + '。'
}

/** Fail closed after an approved financial turn: a missing tool history is never proof of no write. */
export function verifiedWriteResult(result: TurnView, operationId: string): boolean {
  return result.operationId === operationId && result.verification?.verified === true
}

/** A trusted terminal receipt: this approved turn ended without dispatching a write. */
export function provenNotExecuted(result: TurnView, operationId: string): boolean {
  return result.operationId === operationId
    && result.errorCode === 'WRITE_NOT_EXECUTED'
    && result.verification === null
    && !result.batch
}

export class ApiError extends Error {
  constructor(public code: string, public status = 0) { super(friendlyError(code)); }
}
export type PlanState = 'done' | 'current' | 'waiting' | 'optional'
export interface PlanStep { key: string; title: string; state: PlanState; detail: string }
export interface RuleCitation { id: string; version: string; name?: string }

const QUERY_TOOLS = new Set(['query_invoices', 'query_bank_entries', 'check_invoice_purpose', 'query_rules', 'search_knowledge'])

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
  // T35: stated invoice values that no verified write has saved yet keep the save step open.
  const awaiting = task.awaitingSave ?? []
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
    awaiting.length > 0
      ? {key:'write', title:'保存并回读核验', state:writeAttempted && !verified ? 'current' : 'waiting',
        detail:`${writeAttempted && !verified ? '已发起写入但未核验，禁止重复保存；' : ''}待保存：${awaiting.join('、')}（声明不等于已保存）`}
      : {key:'write', title:'保存并回读核验', state:verified ? 'done' : pending ? 'optional' : writeAttempted ? 'current' : 'optional',
        detail:verified ? 'verified=true，数据库回读一致' : writeAttempted ? '已发起写入但未核验，禁止重复保存' : '只读、尚未授权保存'},
    {key:'close', title:'结项证据门', state:completed ? 'done' : waiting ? 'waiting' : 'current',
      detail:completed ? '任务完成且证据门通过' : waiting ? (awaiting.length > 0 && !pending ? '等待确认保存或取消，不能提前结项' : '等待用户补充，不能提前结项') : '执行中，禁止用模型文字结项'}
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

export interface KnowledgeCitation { kind: 'RULE' | 'FILE'; name: string; version: string; location: string }
/** Passages search_knowledge actually returned this turn; a model's own citation is never evidence by itself. */
export function citedKnowledge(history: Exchange[]): KnowledgeCitation[] {
  const found = new Map<string, KnowledgeCitation>()
  for (const exchange of history) {
    if (exchange.call.name !== 'search_knowledge') continue
    const result = parseTool(exchange)
    const hits = (result?.data as {hits?: unknown} | null)?.hits
    if (!result || result.error || !Array.isArray(hits)) continue
    for (const hit of hits) {
      const kind = hit?.source_kind, version = hit?.version, location = hit?.location
      const name = kind === 'RULE' ? hit?.source_id : hit?.source_name
      if ((kind === 'RULE' || kind === 'FILE') && [name, version, location].every(v => typeof v === 'string'))
        found.set(`${kind}:${name}@${version}:${location}`, {kind, name, version, location})
    }
  }
  return [...found.values()]
}

/** T34: what the compressed conversation kept, read from the model input the server actually sent. */
export interface ContextSummary { turnsTotal: number; omittedTurns: number; latestMonth: string | null; latestMonthTurn: number | null; constraints: {turn: number; user: string}[]; ids: string[] }
export function contextSummary(modelInput: string | null | undefined): ContextSummary | null {
  if (!modelInput) return null
  try {
    const conv = JSON.parse(modelInput)?.conversation
    if (!conv || typeof conv !== 'object') return null
    const latest = conv.key_objects?.latest_month
    return {
      turnsTotal: Number(conv.turns_total) || 0,
      omittedTurns: Number(conv.omitted_turns?.count) || 0,
      latestMonth: typeof latest?.value === 'string' ? latest.value : null,
      latestMonthTurn: typeof latest?.turn === 'number' ? latest.turn : null,
      constraints: Array.isArray(conv.earlier_constraints) ? conv.earlier_constraints.filter((c: {turn?: unknown; user?: unknown}) => typeof c?.turn === 'number' && typeof c?.user === 'string') : [],
      ids: Array.isArray(conv.key_objects?.ids_mentioned) ? conv.key_objects.ids_mentioned.filter((v: unknown) => typeof v === 'string') : [],
    }
  } catch { return null }
}

/** Server timestamps are UTC instants (Java `Instant.toString()`); show them in the viewer's local time. */
export function localTime(iso: string | null | undefined, seconds = false): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  const text = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
  return seconds ? `${text}:${pad(date.getSeconds())}` : text
}

/**
 * T51: the month the user most recently named (in the given order, later texts win), as YYYY-MM; null when none.
 * Only pass the user's own words (task goal, user messages), never model text. Never falls back to the current month:
 * R001 says an unclear month is asked for, not assumed.
 */
export function latestMonth(texts: string[]): string | null {
  let found: string | null = null
  for (const text of texts)
    for (const match of text.matchAll(/(20\d{2})\s*(?:-|\/|年)\s*(\d{1,2})(?!\d)/g)) {
      const month = Number(match[2])
      if (month >= 1 && month <= 12) found = `${match[1]}-${String(month).padStart(2, '0')}`
    }
  return found
}
export const MONTH_PATTERN = /^20\d{2}-(0[1-9]|1[0-2])$/
export function friendlyError(code: string) {
  const messages: Record<string, string> = {
    MODEL_NOT_CONFIGURED: '真实模型未配置。请在服务端设置 LLM_ENDPOINT、LLM_API_KEY 和 LLM_MODEL；当前没有模拟回复。',
    MODEL_FAILURE: '模型没有给出可执行的结论；本轮没有据此执行保存。请重试，或查看工具与审计记录确认已发生的只读操作。',
    MODEL_TIMEOUT: '模型响应超时；本轮不能据此声明成功。请以工具和审计记录为准，勿重复写入。',
    MODEL_RESPONSE_INVALID: '模型回复格式无法解析（已按统一策略纠正重试一次）；本轮没有据此执行保存，可稍后重新发送。',
    MODEL_AUTH_FAILED: '模型服务拒绝了凭据；请在服务端检查 API Key 配置。本轮没有执行保存。',
    MODEL_RATE_LIMITED: '模型服务限流；系统不会自动重发，请稍后重新发送。本轮没有执行保存。',
    MODEL_TRANSPORT_ERROR: '无法连接模型服务；请检查网络与 LLM_ENDPOINT。本轮没有执行保存。',
    MODEL_HTTP_ERROR: '模型服务返回错误；本轮没有据此执行保存，可稍后重新发送。',
    MODEL_OUTPUT_LIMIT: '模型回复超过长度上限被截断；本轮没有据此执行保存，请缩小问题范围。',
    TOOL_ERRORS_REPEATED: '连续多次工具调用出错，系统已停止重试；请核对企业、月份或权限后再提问。',
    STORAGE_ERROR: '存储读写失败。若本轮涉及保存，结果未知：请先“核验”，不要重复保存。',
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
    UNSUPPORTED_SCOPE: '超出当前支持范围：文件类型与内容不符或不受支持，或没有适用的规则。',
    REQUEST_TOO_LARGE: '请求超过大小上限（上传文件最大 10 MB）。',
    LENGTH_REQUIRED: '上传请求缺少长度信息，请重新选择文件上传。',
    NOT_FOUND: '没有找到该记录，或它不属于当前企业。',
    CAPABILITY_NOT_GRANTED: '当前 Agent 没有被授予这项能力；可在“Agent 配置”中调整，或换用其他 Agent。',
    CAPABILITY_DISABLED: '这项能力已在“能力中心”停用；重新启用前任何 Agent 都不能使用。',
  }
  return `${messages[code] ?? '请求未完成，请核查服务、权限与实际执行结果。'} (${code})`
}

/** Minimal SSE parser for Spring's `event:name\ndata:{json}\n\n` frames. */
function parseSseBlock(block: string): {event: string; data: unknown} | null {
  let event = 'message'
  const data: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  if (!data.length) return null
  try { return {event, data: JSON.parse(data.join('\n'))} } catch { return null }
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
  start(goal: string, pending: string[], agentId?: string) { return this.request<Task>('/tasks', 'POST', {goal, pending, ...(agentId ? {agentId} : {})}) }
  agents() { return this.request<AgentsView>('/agents') }
  agentHistory(id: string) { return this.request<AgentDef[]>(`/agents/${encodeURIComponent(id)}/history`) }
  /** Creates the agent or saves a new version; existing versions are never rewritten. */
  saveAgent(id: string, spec: AgentSpec) { return this.request<AgentDef>('/agents', 'POST', {id, spec}) }
  capabilities() { return this.request<Capabilities>('/capabilities') }
  setToolEnabled(name: string, enabled: boolean) { return this.request<ToolInfo>(`/capabilities/tools/${encodeURIComponent(name)}/enabled`, 'POST', {enabled}) }
  saveSkill(id: string, spec: SkillSpec) { return this.request<Skill>('/capabilities/skills', 'POST', {id, spec}) }
  setSkillEnabled(id: string, enabled: boolean) { return this.request<Skill>(`/capabilities/skills/${encodeURIComponent(id)}/enabled`, 'POST', {enabled}) }
  skillHistory(id: string) { return this.request<Skill[]>(`/capabilities/skills/${encodeURIComponent(id)}/history`) }
  setAgentEnabled(id: string, enabled: boolean) { return this.request<AgentDef>(`/agents/${encodeURIComponent(id)}/enabled`, 'POST', {enabled}) }
  /** Explicit user action only. Cancelling a task never modifies or rolls back financial records. */
  cancel(id: string, version: number) { return this.request<Task>(`/tasks/${encodeURIComponent(id)}/cancel`, 'POST', {version, confirmed: true}) }
  load(id: string) { return this.request<Context>(`/tasks/${encodeURIComponent(id)}`) }
  supplement(id: string, version: number, statements: Record<string, Statement>) { return this.request<Task>(`/tasks/${encodeURIComponent(id)}/supplement`, 'POST', {version, statements}) }
  turn(id: string, message: string, approval?: Approval) { return this.request<TurnView>(`/tasks/${encodeURIComponent(id)}/turn`, 'POST', {message, ...(approval ? {approval} : {})}) }
  /** Same execution as turn(), streamed as SSE: tool progress, then text chunks, then the terminal TurnView. */
  async streamTurn(id: string, message: string, approval: Approval | undefined,
                   handlers: {onText?: (text: string) => void; onTool?: (event: ToolProgress) => void} = {}): Promise<TurnView> {
    let response: Response
    try {
      response = await fetch(`/api/demo/tasks/${encodeURIComponent(id)}/turn/stream`, {
        method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: {'Content-Type': 'application/json', 'X-CSRF-Token': this.csrf},
        body: JSON.stringify({message, ...(approval ? {approval} : {})})
      })
    } catch { throw new ApiError('NETWORK_UNKNOWN') }
    if (!response.ok) {
      let code = `HTTP_${response.status}`
      try { const body = await response.json() as {errorCode?: string}; code = body.errorCode ?? code } catch { /* keep status code */ }
      throw new ApiError(code, response.status)
    }
    if (!response.body) throw new ApiError('INVALID_RESPONSE', response.status)
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = '', final: TurnView | null = null, failure: string | null = null
    while (true) {
      const chunk = await reader.read()
      if (chunk.value) buffer += decoder.decode(chunk.value, {stream: !chunk.done})
      for (let cut = buffer.indexOf('\n\n'); cut >= 0; cut = buffer.indexOf('\n\n')) {
        const block = buffer.slice(0, cut); buffer = buffer.slice(cut + 2)
        const parsed = parseSseBlock(block)
        if (!parsed) continue
        if (parsed.event === 'text') handlers.onText?.(String((parsed.data as {text?: unknown})?.text ?? ''))
        else if (parsed.event === 'tool') handlers.onTool?.(parsed.data as ToolProgress)
        else if (parsed.event === 'error') failure = String((parsed.data as {errorCode?: unknown})?.errorCode ?? 'RUNTIME_FAILURE')
        else if (parsed.event === 'done') final = parsed.data as TurnView
      }
      if (chunk.done) break
    }
    if (failure) throw new ApiError(failure)
    if (!final) throw new ApiError('INVALID_RESPONSE')
    return final
  }
  preferences() { return this.request<Record<string, Statement>>('/preferences') }
  putPreference(name: string, value: string, userRequest: string) { return this.request<Record<string, Statement>>('/preferences', 'POST', {name, value, userRequest}) }
  deletePreference(name: string) { return this.request<Record<string, Statement>>(`/preferences/${encodeURIComponent(name)}`, 'DELETE') }
  memories() { return this.request<Memory[]>('/memories') }
  putMemory(scope: MemoryScope, name: string, value: string, userRequest: string, expiresOn: string | null) {
    return this.request<Memory[]>('/memories', 'POST', {scope, name, value, userRequest, expiresOn})
  }
  /** Erases the memory and its values in stored model inputs; it cannot be restored. */
  deleteMemory(scope: MemoryScope, name: string) { return this.request<Memory[]>(`/memories/${scope}/${encodeURIComponent(name)}`, 'DELETE') }
  verify(taskId: string, operationId: string) { return this.request<OperationVerification>(`/tasks/${encodeURIComponent(taskId)}/operations/${encodeURIComponent(operationId)}/verify`, 'POST') }
  audit() { return this.request<AuditEvent[]>('/audit') }
  /** The same sanitized audit metadata as CSV, with a BOM so Excel reads it as UTF-8. Read-only. */
  async exportAudit(): Promise<Blob> {
    let response: Response
    try {
      response = await fetch('/api/demo/audit/export', {credentials: 'same-origin', cache: 'no-store', headers: {'X-CSRF-Token': this.csrf}})
    } catch { throw new ApiError('NETWORK_UNKNOWN') }
    if (!response.ok) {
      let code = `HTTP_${response.status}`
      try { code = ((await response.json()) as {errorCode?: string}).errorCode ?? code } catch { /* keep the HTTP code */ }
      throw new ApiError(code, response.status)
    }
    return new Blob(['\uFEFF', await response.text()], {type: 'text/csv;charset=utf-8'})
  }
  /** Multipart upload; the browser sets the boundary and length. Never retried automatically.
   * T46: mappings, when given, are the accountant's explicit column assignment for the named sheets. */
  async upload(file: File, mappings?: SheetMapping[]): Promise<UploadResult> {
    const form = new FormData()
    form.append('file', file, file.name)
    if (mappings?.length) form.append('mapping', JSON.stringify(mappings))
    return this.multipart<UploadResult>('/api/demo/files', form)
  }
  /** T48: stores the scanned PDF and returns the model's draft (or a manual one); nothing is imported yet. */
  scan(file: File): Promise<ScanDraft> {
    const form = new FormData()
    form.append('file', file, file.name)
    return this.multipart<ScanDraft>('/api/demo/files/scan', form)
  }
  confirmScan(draftId: string, confirmation: ScanConfirmation) { return this.request<ScanImport>(`/files/scan/${encodeURIComponent(draftId)}/confirm`, 'POST', confirmation) }
  private async multipart<T>(url: string, form: FormData): Promise<T> {
    let response: Response
    try {
      response = await fetch(url, {method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: {'X-CSRF-Token': this.csrf}, body: form})
    } catch { throw new ApiError('NETWORK_UNKNOWN') }
    let data: T & {errorCode?: string}
    try { data = await response.json() } catch { throw new ApiError('INVALID_RESPONSE', response.status) }
    if (!response.ok) throw new ApiError(data.errorCode ?? `HTTP_${response.status}`, response.status)
    return data
  }
  files() { return this.request<StoredFile[]>('/files') }
  /** Takes effect on the next search; the stored file never changes. */
  setKnowledge(id: string, enabled: boolean) { return this.request<StoredFile>(`/files/${encodeURIComponent(id)}/knowledge`, 'POST', {enabled}) }
  file(id: string) { return this.request<FileDetail>(`/files/${encodeURIComponent(id)}`) }
  /** Persisted model inputs; survives reload and restart, newest first. */
  traces(id: string) { return this.request<SavedTrace[]>(`/tasks/${encodeURIComponent(id)}/traces`) }
}

/** Creating a task and running its first turn are separate backend operations.
 * The callback publishes the created task immediately, even if the model call fails.
 * A failed first turn is never retried automatically. */
export async function createAndRunFirstTurn(
  api: Pick<DemoApi, 'start' | 'turn'> & Partial<Pick<DemoApi, 'streamTurn'>>,
  goal: string,
  pending: string[],
  onCreated: (task: Task) => void,
  handlers?: {onText?: (text: string) => void; onTool?: (event: ToolProgress) => void},
  agentId?: string,
): Promise<{created: Task; response: TurnView | null; executionError: Error | null}> {
  // Without a choice the server binds the default agent; the call stays exactly as before.
  const created = agentId ? await api.start(goal, pending, agentId) : await api.start(goal, pending)
  onCreated(created)
  try {
    const response = handlers && api.streamTurn
      ? await api.streamTurn(created.id, goal, undefined, handlers)
      : await api.turn(created.id, goal)
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
