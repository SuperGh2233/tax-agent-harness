import { useEffect, useRef, useState } from 'react'
import type { FormEvent, KeyboardEvent, ReactNode, RefObject } from 'react'
import { ArrowUp, ArrowUpRight, Bot, Check, ChevronRight, CircleDashed, FileCheck2, FileUp, History, PanelRight, ShieldCheck, Sparkles } from 'lucide-react'
import { AGENT_TOOLS, agentProblem, ApiError, contextSummary, memoryProblem, SCOPE_LABEL, impactMessage, skillProblem, canVerifyOperation, citedKnowledge, citedRules, createAndRunFirstTurn, DemoApi, deriveExecutionPlan, describeUpload, findOpenTask, friendlyError, isTerminalTaskStatus, issueLocation, issueReason, parseTool, provenNotExecuted, queriedInvoices, UPLOAD_ACCEPT, uploadProblem, usableAgents, verifiedWriteResult, verifyPendingOperation } from './api'
import type { Memory, MemoryScope, AgentDef, AgentSpec, AgentsView, Capabilities, Skill, SkillInfo, SkillSpec, ToolInfo, Approval, AuditEvent, Exchange, FileDetail, Invoice, PlanStep, RowIssue, SavedTrace, Session, Statement, StoredFile, Task, ToolProgress, TurnView, UploadResult } from './api'
import { Button, Modal } from './ui'

const api = new DemoApi()
type Page = 'home' | 'workspace' | 'review' | 'history' | 'files' | 'agents' | 'capabilities' | 'audit'
type Message = {role: 'user' | 'agent' | 'notice'; text: string; history?: Exchange[]}
type Operation = {id: string; taskId: string; unknown: boolean}
const suggestions = ['查询 2026-09 发票与银行收支', '检查 2026-09 发票用途缺口', '继续核对待补充的发票信息']
const statusLabel = {ACTIVE: '进行中', WAITING_USER: '等待补充', COMPLETED: '已结束', CANCELLED: '已取消'}
const OPERATION_KEY = 'taxagent-pending-operation'
const SHARED_OPERATION_KEY = 'taxagent-pending-operation-shared'
const OPERATION_CHANNEL = 'taxagent-operation-lock'
const pretty = (value: unknown) => JSON.stringify(value, null, 2)
// eslint-disable-next-line react-refresh/only-export-components -- Pure storage initializer shared with its regression tests.
export function initialOperation(read = () => sessionStorage.getItem(OPERATION_KEY)): Operation | null {
  const unknown: Operation = {id:'unknown', taskId:'unknown', unknown:true}
  try {
    let raw = read()
    if (raw === null && typeof localStorage !== 'undefined') raw = localStorage.getItem(SHARED_OPERATION_KEY)
    if (raw === null) return null
    const value = JSON.parse(raw) as Partial<Operation> | null
    if (value && typeof value === 'object' && !Array.isArray(value)
      && typeof value.id === 'string' && value.id.trim()
      && typeof value.taskId === 'string' && value.taskId.trim() && value.unknown === true)
      return {id:value.id, taskId:value.taskId, unknown:true}
  } catch { /* Unreadable storage cannot prove there is no pending write. */ }
  return unknown
}

export default function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [tasks, setTasks] = useState<Task[]>([])
  const [task, setTask] = useState<Task | null>(null)
  const [page, setPage] = useState<Page>('home')
  const [busy, setBusy] = useState(true)
  const lock = useRef(true)
  const channelRef = useRef<BroadcastChannel | null>(null)
  const [error, setError] = useState('')
  const [identityOpen, setIdentityOpen] = useState(false)
  const [preferencesOpen, setPreferencesOpen] = useState(false)
  const [preferences, setPreferences] = useState<Record<string, Statement>>({})
  const [prefName, setPrefName] = useState('')
  const [prefValue, setPrefValue] = useState('')
  const [prefRequest, setPrefRequest] = useState('')
  const [prefScope, setPrefScope] = useState<MemoryScope>('USER_ENTERPRISE')
  const [prefExpiry, setPrefExpiry] = useState('')
  const [memories, setMemories] = useState<Memory[]>([])
  const [cancelCandidate, setCancelCandidate] = useState<Task | null>(null)
  const [inspector, setInspector] = useState(false)
  const [messages, setMessages] = useState<Message[]>([])
  const [turn, setTurn] = useState<TurnView | null>(null)
  const [streamingText, setStreamingText] = useState('')
  const [liveProgress, setLiveProgress] = useState('')
  const [operation, setOperation] = useState<Operation | null>(initialOperation)
  const [audit, setAudit] = useState<AuditEvent[] | null>(null)
  const [savedTrace, setSavedTrace] = useState<SavedTrace | null>(null)
  const [goal, setGoal] = useState('')
  const [pending, setPending] = useState('')
  const [sourceTaskId, setSourceTaskId] = useState<string | null>(null)
  const [loadedHistoricalTask, setLoadedHistoricalTask] = useState(false)
  const [files, setFiles] = useState<StoredFile[] | null>(null)
  const [upload, setUpload] = useState<UploadResult | null>(null)
  const [fileDetail, setFileDetail] = useState<FileDetail | null>(null)
  const [agentsView, setAgentsView] = useState<AgentsView | null>(null)
  const [agentChoice, setAgentChoice] = useState('')
  const [taskAgent, setTaskAgent] = useState<AgentDef | null>(null)
  const [capabilities, setCapabilities] = useState<Capabilities | null>(null)
  const [pendingSwitch, setPendingSwitch] = useState<{kind: '工具' | '技能'; name: string; usedBy: string[]; enabling: boolean} | null>(null)
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => { heading.current?.focus() }, [page])
  useEffect(() => {
    const channel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(OPERATION_CHANNEL)
    channelRef.current = channel
    if (channel) channel.onmessage = event => {
      const value = event.data?.operation as Operation | null | undefined
      if (value === null) { try { sessionStorage.removeItem(OPERATION_KEY); setOperation(null) } catch { /* keep lock */ } return }
      if (value && typeof value.id === 'string' && typeof value.taskId === 'string' && value.unknown === true) {
        try { sessionStorage.setItem(OPERATION_KEY, pretty(value)); setOperation(value) } catch { /* keep lock */ }
      }
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key !== SHARED_OPERATION_KEY) return
      try {
        if (event.newValue === null) { sessionStorage.removeItem(OPERATION_KEY); setOperation(null); return }
        const value = JSON.parse(event.newValue) as Partial<Operation> | null
        if (value && typeof value.id === 'string' && typeof value.taskId === 'string' && value.unknown === true) {
          sessionStorage.setItem(OPERATION_KEY, pretty(value)); setOperation({id:value.id, taskId:value.taskId, unknown:true})
        }
      } catch { /* malformed or unavailable storage keeps the in-memory lock */ }
    }
    window.addEventListener('storage', onStorage)
    return () => { channel?.close(); channelRef.current = null; window.removeEventListener('storage', onStorage) }
  }, [])
  function rememberOperation(value: Operation | null) {
    if (value && operation) throw new Error('已有待核验操作，禁止覆盖或再次保存。')
    // Failed removal keeps both the in-memory and persisted locks intact.
    if (!value) {
      sessionStorage.removeItem(OPERATION_KEY)
      if (typeof localStorage !== 'undefined') localStorage.removeItem(SHARED_OPERATION_KEY)
    }
    setOperation(value)
    // A failed setItem must stop send() before its financial request; the in-memory lock remains.
    if (value) {
      sessionStorage.setItem(OPERATION_KEY, pretty(value))
      if (typeof localStorage !== 'undefined') localStorage.setItem(SHARED_OPERATION_KEY, pretty(value))
    }
    channelRef.current?.postMessage({operation:value})
  }
  async function run(work: () => Promise<void>) {
    if (lock.current) return
    lock.current = true; setBusy(true); setError('')
    try { await work() } catch (e) { setError(e instanceof Error ? e.message : '请求未完成') }
    finally { lock.current = false; setBusy(false) }
  }
  const streamHandlers = {
    onText: (delta: string) => { setStreamingText(old => old + delta); setLiveProgress('') },
    onTool: (event: ToolProgress) => setLiveProgress(event.phase === 'tool_start'
      ? `正在调用 ${event.name}…`
      : `${event.name} 已返回${event.outcome && event.outcome !== 'SUCCESS' ? ` · ${event.outcome}` : ''}`),
  }
  function clearStream() { setStreamingText(''); setLiveProgress('') }
  useEffect(() => {
    let mounted = true
    void api.session().then(async s => {
      if (!mounted) return
      setSession(s)
      if (s.profile) { const list = await api.tasks(); if (mounted) setTasks(list) }
    }).catch(e => { if (mounted) setError(e instanceof Error ? e.message : '连接失败') }).finally(() => { if (mounted) { lock.current = false; setBusy(false) } })
    return () => { mounted = false }
  }, [])
  const selected = !!session?.profile
  const openTask = findOpenTask(tasks)
  const history = turn?.turn?.result.history ?? []
  const verification = turn?.verification ?? null
  const invoices = queriedInvoices(history, session?.enterprise ?? '')
  async function openInspector() {
    setInspector(true)
    if (!selected) return
    try { setPreferences(await api.preferences()) } catch { /* task inspector remains usable */ }
    // Reload-safe fallback: the in-memory turn trace disappears on refresh, the stored one does not.
    if (task) {
      try { setSavedTrace((await api.traces(task.id))[0] ?? null) } catch { setSavedTrace(null) }
    }
  }
  function reset() { setMemories([]); setCapabilities(null); setPendingSwitch(null); setAgentsView(null); setAgentChoice(''); setTaskAgent(null); setFiles(null); setUpload(null); setFileDetail(null); setTask(null); setCancelCandidate(null); setLoadedHistoricalTask(false); setSourceTaskId(null); setMessages([]); setTurn(null); setSavedTrace(null); setAudit(null); setTasks([]); setInspector(false); setPage('home'); setPreferences({}); setPreferencesOpen(false) }
  async function load(id: string) {
    await run(async () => {
      const context = await api.load(id)
      setTaskAgent(context.agent ?? null)
      setTask(context.task); setLoadedHistoricalTask(true); setTurn(null); setSavedTrace(null); setPage('workspace')
      setMessages([{role:'notice', text:'任务已恢复。历史不是当前数据库证据，恢复不会恢复写入授权；请重新查询。'},
        ...(context.task.question ? [{role:'agent' as const, text:`历史追问：${context.task.question}`}] : [])])
    })
  }
  function startNewFromHistory() {
    if (!task || !isTerminalTaskStatus(task.status)) return
    setGoal(task.goal)
    setPending('')
    setSourceTaskId(task.id)
    setTask(null)
    setLoadedHistoricalTask(false)
    setMessages([])
    setTurn(null)
    setAudit(null)
    setInspector(false)
    setPage('home')
  }
  async function cancelOpenTask() {
    const selectedTask = cancelCandidate
    if (!selectedTask || operation) return
    await run(async () => {
      try {
        const cancelled = await api.cancel(selectedTask.id, selectedTask.version)
        setTasks(old => old.map(t => t.id === cancelled.id ? cancelled : t))
        if (task?.id === cancelled.id) {
          setTask(cancelled)
          setLoadedHistoricalTask(true)
          setTurn(null)
        }
        setCancelCandidate(null)
      } catch (e) {
        if (e instanceof ApiError && e.code === 'CONFLICT') {
          try { setTasks(await api.tasks()) } catch { /* Leave current list until refresh succeeds. */ }
          setCancelCandidate(null)
          throw new Error('任务状态已有变化，请刷新任务列表核对后再操作。')
        }
        if (e instanceof ApiError && ['NETWORK_UNKNOWN','INVALID_RESPONSE','AUDIT_STORAGE_ERROR'].includes(e.code)) {
          try { setTasks(await api.tasks()) } catch { /* Cancellation outcome remains unknown. */ }
          setCancelCandidate(null)
          throw new Error('结束任务的请求结果未确认。请先刷新任务状态确认是否已结束，不要重复点击取消。')
        }
        throw e
      }
    })
  }
  async function start(event: FormEvent) {
    event.preventDefault()
    if (!selected) { setIdentityOpen(true); return }
    if (openTask) {
      setError('当前企业已有未完成任务。请继续处理，或明确结束旧任务后再创建新的任务。')
      return
    }
    const requestedGoal = goal.trim()
    if (!requestedGoal) return
    const requestedPending = pending.split(',').map(s => s.trim()).filter(Boolean)
    await run(async () => {
      clearStream()
      let launch: Awaited<ReturnType<typeof createAndRunFirstTurn>>
      try {
        launch = await createAndRunFirstTurn(api, requestedGoal, requestedPending, created => {
          // Publish the new task before calling the model; model failure must not hide a successful creation.
          setTask(created)
          setTaskAgent(usableAgents(agentsView?.agents ?? [], session?.enterprise ?? '').find(a => a.id === (agentChoice || 'finance-assistant')) ?? null)
          setLoadedHistoricalTask(false)
          setTurn(null)
          setMessages([{role:'user', text:requestedGoal}])
          setSourceTaskId(null)
          setPage('workspace')
          setTasks(old => [created, ...old.filter(t => t.id !== created.id)])
        }, streamHandlers, agentChoice || undefined)
      } catch (e) {
        if (e instanceof ApiError && e.code === 'CONFLICT') {
          try {
            const latest = await api.tasks()
            setTasks(latest)
            if (findOpenTask(latest)) {
              setError('检测到当前企业已有未完成任务。可继续旧任务，或明确结束旧任务后再新建。')
              return
            }
          } catch { /* Server remains the source of truth; never retry start automatically. */ }
        }
        if (e instanceof ApiError && (e.code === 'NETWORK_UNKNOWN' || e.code === 'INVALID_RESPONSE')) {
          throw new Error('新建任务请求结果未知，请先刷新任务历史确认是否已创建，勿直接重复提交。')
        }
        throw e
      }
      if (launch.response) {
        const response = launch.response
        setTurn(response)
        if (response.turn) {
          const updated = response.turn.task
          setTask(updated)
          setTasks(old => [updated, ...old.filter(t => t.id !== updated.id)])
          setMessages(old => [...old, {
            role: response.turn!.result.status === 'ERROR' ? 'notice' : 'agent',
            text: response.turn!.result.text,
            history: response.turn!.result.history,
          }])
        } else {
          setMessages(old => [...old, {role:'notice', text:response.message || '任务已创建，但首轮没有可确认的执行结果。'}])
        }
        if (response.errorCode) setError(friendlyError(response.errorCode))
      } else if (launch.executionError) {
        setError(launch.executionError.message)
        setMessages(old => [...old, {role:'notice', text:'任务已创建，但首轮执行没有成功完成。请检查错误与审计，确认状态后再决定是否继续；不会自动重试。'}])
      }
      clearStream()
      try {
        setTasks(await api.tasks())
      } catch {
        // Do not turn a successful creation into an apparent failure if only the list refresh fails.
        setMessages(old => [...old, {role:'notice', text:'任务已创建，但任务列表暂时无法刷新；请稍后查看任务历史，勿重复创建。'}])
      }
    })
  }
  async function send(message: string, approval?: Approval, continueNew = false) {
    if (!task) return
    await run(async () => {
      clearStream(); setTurn(null)
      let target = task
      if (continueNew) {
        target = await api.start(`${task.goal}（继续）`, [])
        setTask(target)
        setTasks(old => [target, ...old.filter(t => t.id !== target.id)])
        setMessages([{role:'notice', text:'原任务已结束，已基于原目标新建任务；本轮写入仍需重新确认。'}, {role:'user', text:message}])
      } else {
        setMessages(old => [...old, {role:'user', text:message}])
      }
      if (approval) rememberOperation({id:approval.operationId, taskId:target.id, unknown:true})
      let result: TurnView
      try { result = await api.streamTurn(target.id, message, approval, streamHandlers) }
      catch (e) {
        clearStream()
        if (approval) throw new Error(`operationId=${approval.operationId} · 写入结果未确认，禁止自动重试。请核查审计与数据库。原始错误：${e instanceof Error ? e.message : '请求异常'}`)
        throw e
      }
      clearStream()
      setTurn(result)
      setLoadedHistoricalTask(false)
      const notExecuted = approval !== undefined && provenNotExecuted(result, approval.operationId)
      if (result.turn) {
        setTask(result.turn.task)
        setMessages(old => [...old, {role:result.turn!.result.status === 'ERROR' ? 'notice' : 'agent', text:notExecuted && result.message ? result.message : result.turn!.result.text, history:result.turn!.result.history}])
      }
      // A timeout or empty history is never proof of no write. Only a matching verified
      // readback, or the explicit WRITE_NOT_VERIFIED terminal receipt, may release the lock.
      if (approval && verifiedWriteResult(result, approval.operationId)) rememberOperation(null)
      else if (notExecuted) rememberOperation(null)
      if (result.errorCode) setError(friendlyError(result.errorCode))
      setAudit(null)
      setTasks(await api.tasks())
    })
  }
  async function showAudit() { await run(async () => { setAudit(await api.audit()) }) }
  async function refreshAgents() { await run(async () => { setAgentsView(await api.agents()); setCapabilities(await api.capabilities()) }) }
  async function refreshCapabilities() { await run(async () => { setCapabilities(await api.capabilities()) }) }
  async function confirmSwitch() {
    const change = pendingSwitch
    if (!change) return
    await run(async () => {
      if (change.kind === '工具') await api.setToolEnabled(change.name, change.enabling)
      else await api.setSkillEnabled(change.name, change.enabling)
      setPendingSwitch(null)
      setCapabilities(await api.capabilities()); setAgentsView(await api.agents())
    })
  }
  async function saveSkill(id: string, spec: SkillSpec) {
    const problem = skillProblem(id, spec)
    if (problem) throw new Error(problem)
    await api.saveSkill(id, spec)
    setCapabilities(await api.capabilities()); setAgentsView(await api.agents())
  }
  useEffect(() => {
    if (!session?.profile) return
    let live = true
    // Composer choices only; a failure leaves the default agent, which the server binds anyway.
    void api.agents().then(view => { if (live) setAgentsView(view) }).catch(() => {})
    return () => { live = false }
  }, [session?.profile, session?.enterprise])
  async function saveAgent(id: string, spec: AgentSpec) {
    const problem = agentProblem(id, spec)
    if (problem) throw new Error(problem)
    await api.saveAgent(id, spec)
    setAgentsView(await api.agents())
  }
  async function toggleAgent(agent: AgentDef) {
    await run(async () => { await api.setAgentEnabled(agent.id, !agent.enabled); setAgentsView(await api.agents()) })
  }
  async function refreshFiles() { await run(async () => { setFiles(await api.files()) }) }
  async function uploadFile(file: File) {
    const problem = uploadProblem(file)
    if (problem) { setError(problem); return }
    await run(async () => {
      setUpload(null); setFileDetail(null)
      try { setUpload(await api.upload(file)) }
      catch (e) {
        // A lost response may still have stored the file; the list is the source of truth, not a retry.
        if (e instanceof ApiError && (e.code === 'NETWORK_UNKNOWN' || e.code === 'INVALID_RESPONSE')) {
          try { setFiles(await api.files()) } catch { /* list stays as it was */ }
          throw new Error('上传结果未确认。请查看下方文件列表确认是否已保存；重复上传相同内容不会产生新数据。')
        }
        throw e
      }
      setFiles(await api.files())
    })
  }
  async function openFile(id: string) { await run(async () => { setFileDetail(await api.file(id)) }) }
  async function toggleKnowledge(file: StoredFile) {
    await run(async () => { await api.setKnowledge(file.id, !file.knowledgeEnabled); setFiles(await api.files()) })
  }
  async function openPreferences() {
    setPreferencesOpen(true)
    setMemories([]) // Never show a previous identity's memories while loading.
    await run(async () => { setMemories(await api.memories()) })
  }
  async function savePreference(event: FormEvent) {
    event.preventDefault()
    await run(async () => {
      const problem = memoryProblem(prefScope, prefName, prefValue, prefRequest, prefExpiry, new Date().toISOString().slice(0, 10))
      if (problem) throw new Error(problem)
      setMemories(await api.putMemory(prefScope, prefName.trim(), prefValue, prefRequest, prefExpiry || null))
      // Clear right after the save: clearing after a later request would erase anything typed meanwhile.
      setPrefName(''); setPrefValue(''); setPrefRequest(''); setPrefExpiry('')
      setPreferences(await api.preferences())
    })
  }
  async function removeMemory(memory: Memory) {
    await run(async () => { setMemories(await api.deleteMemory(memory.scope, memory.name)); setPreferences(await api.preferences()) })
  }
  async function verifyOperation() {
    if (!operation) return
    await run(async () => {
      await verifyPendingOperation(api, operation, () => rememberOperation(null))
      setMessages(old => [...old, {role:'notice', text:'VERIFIED · 已提交操作与当前发票快照一致；未重新执行写入。'}])
      setAudit(null)
    })
  }
  const navigate = (next: Page) => {
    setPage(next); setInspector(false)
    if (next === 'files' && selected && files === null && !lock.current) void refreshFiles()
    if (next === 'agents' && selected && !lock.current) void refreshAgents()
    if (next === 'capabilities' && selected && !lock.current) void refreshCapabilities()
  }
  const taskList = (limit?: number) => <div className="task-list">{tasks.length ? tasks.slice(0, limit).map(t => <button className="task-row" key={t.id} disabled={busy} onClick={() => void load(t.id)}><History size={17}/><span>{t.goal}<small>{t.id.slice(0,8)} · {statusLabel[t.status]}</small></span><ArrowUpRight size={17}/></button>) : <p className="empty">{selected ? '还没有任务，从一个具体问题开始。' : '选择企业与身份后，查看你的任务。'}</p>}</div>

  return <div className="app-shell">
    <a className="skip-link" href="#main">跳到主要内容</a>
    <header className="topbar"><button className="brand" onClick={() => navigate('home')} aria-label="财税 Agent 工作台"><span className="brand-mark"><Sparkles size={20}/></span><span>财税 Agent<small>HARNESS</small></span></button>
      <nav aria-label="主导航">{([['home','工作台'],['history','任务历史'],['files','资料文件'],['agents','Agent 配置'],['capabilities','能力中心'],['audit','审计记录']] as const).map(([value,label]) => <button key={value} aria-current={page === value ? 'page' : undefined} onClick={() => navigate(value)}>{label}</button>)}</nav>
      <Button variant="ghost" onClick={() => void openPreferences()} disabled={!selected || busy}>长期记忆</Button><Button className="identity-button" onClick={() => setIdentityOpen(true)} disabled={busy}>{selected ? `${session.enterprise} · ${session.profile === 'operator' ? '操作员' : '只读成员'}` : '选择企业 / 身份'}<ChevronRight size={14}/></Button>
    </header>
    <div className="environment-line"><span className="dot"/> 本地演示工作空间 <span>·</span> {session?.identityNotice ?? '正在连接本地服务'}</div>
    <main id="main" tabIndex={-1} className={page === 'workspace' ? 'main workspace-main' : 'main'}>
      {error && <div role="alert" className="error-banner">{error}<Button variant="ghost" disabled={busy} onClick={() => void run(async () => { const s = await api.session(); setSession(s); if (s.profile) setTasks(await api.tasks()) })}>重新连接</Button></div>}
      {operation && <aside className="operation-warning" role="status">保存请求需核查 · operationId: <code>{operation.id}</code><br/>任务 {operation.taskId}。结果未核验前保持锁定；NOT_FOUND 不允许重试，DIVERGED 需人工核查。编号保留在本浏览器会话中。<Button disabled={busy || !selected || !canVerifyOperation(operation)} onClick={() => void verifyOperation()}>只读核验保存结果</Button>{!canVerifyOperation(operation) && <p>存储编号无法识别，请人工核查。</p>}</aside>}
      {page === 'home' && <div className="home">
        <section className="hero"><span className="eyebrow"><Sparkles size={14}/> YOUR FINANCE WORKSPACE</span><h1 ref={heading} tabIndex={-1}>今天，交给 Agent 什么任务？</h1><p>从一个问题开始，让每一步执行都有据可查。</p>
          {sourceTaskId && <p className="source-task-note"><History size={14}/> 已带入历史任务目标（{sourceTaskId.slice(0,8)}）。创建后会生成全新的任务，不会恢复旧任务的写入授权。</p>}
          {openTask && <section className="open-task-banner" role="status" aria-label="已有未完成任务">
            <div><span className="open-task-label"><History size={15}/> 当前有未完成任务 · {statusLabel[openTask.status]}</span>
              <p>{openTask.goal}</p><small>每个用户在同一家企业同时只能有一项进行中任务。继续原任务，或手动结束后再新建；不会自动取消或重复提交。</small></div>
            <div className="open-task-actions"><Button disabled={busy} variant="primary" onClick={() => void load(openTask.id)}>继续任务 <ArrowUpRight size={14}/></Button>
              <Button disabled={busy || !!operation} onClick={() => setCancelCandidate(openTask)}>结束旧任务</Button>
              <Button variant="ghost" disabled={busy} onClick={() => void run(async () => setTasks(await api.tasks()))}>刷新状态</Button></div>
          </section>}
          <form onSubmit={start} className="goal-composer"><label className="sr-only" htmlFor="goal">任务目标</label><textarea id="goal" value={goal} onChange={e => { setGoal(e.target.value); if (sourceTaskId) setSourceTaskId(null) }} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); e.currentTarget.form?.requestSubmit() } }} placeholder="查询九月发票与银行收支，并检查发票用途…" required maxLength={4000}/><div className="composer-bottom"><span><ShieldCheck size={14}/> 写入前由你确认</span><Button variant="primary" type="submit" disabled={busy || !goal.trim() || !!openTask} aria-label={openTask ? '存在未完成任务，暂不能新建' : '创建任务'}><ArrowUp size={18}/></Button></div>
          <AgentPicker agents={usableAgents(agentsView?.agents ?? [], session?.enterprise ?? '')} value={agentChoice} onChange={setAgentChoice} disabled={busy || !selected}/>
          <details className="pending"><summary>添加待补充字段（可选）</summary><label>字段，以逗号分隔<input value={pending} onChange={e => setPending(e.target.value)} placeholder="F002.purpose"/></label></details></form>
          <div className="suggestions">{suggestions.map((s,i) => <button key={s} onClick={() => { setGoal(s); setSourceTaskId(null); document.getElementById('goal')?.focus() }}><span>0{i+1}</span>{s}<ArrowUpRight size={14}/></button>)}</div>
        </section>
        <section className="recent"><div className="section-heading"><h2>最近任务</h2><Button variant="ghost" onClick={() => navigate('history')}>全部任务 <ArrowUpRight size={14}/></Button></div>{taskList(4)}</section>
      </div>}
      {page === 'history' && <section className="content-page"><div className="page-heading"><div><span className="eyebrow">YOUR TASKS</span><h1 ref={heading} tabIndex={-1}>任务历史</h1><p className="muted">恢复上下文，重新核对当前数据。</p></div><Button disabled={!selected || busy} onClick={() => void run(async () => setTasks(await api.tasks()))}>刷新任务</Button></div>{taskList()}</section>}
      {page === 'workspace' && task && <>
        <header className="workspace-heading"><div><button className="back" onClick={() => navigate('home')}>工作台 /</button><h1 ref={heading} tabIndex={-1}>{task.goal}</h1><span className="muted">{statusLabel[task.status]} · {session?.enterprise} · {task.id.slice(0,8)}{taskAgent && <> · <Bot size={13} className="inline-icon"/> {taskAgent.spec.name} v{taskAgent.version}{!taskAgent.enabled && '（已停用）'}</>}</span></div><div className="workspace-actions"><Button disabled={busy || !turn || (loadedHistoricalTask && isTerminalTaskStatus(task.status))} onClick={() => navigate('review')}><FileCheck2 size={16}/>审核与结果</Button><Button aria-label="打开检查器" onClick={() => void openInspector()}><PanelRight size={17}/></Button></div></header>
        {loadedHistoricalTask && isTerminalTaskStatus(task.status) ? <TerminalTaskPanel task={task} onCreateNew={startNewFromHistory}/> : <>
          <Thread messages={messages} busy={busy} streaming={streamingText} progress={liveProgress}/>
          <Composer disabled={busy || !selected} onSend={m => void send(m, undefined, isTerminalTaskStatus(task.status))}/>
          {!isTerminalTaskStatus(task.status) && <details className="supplement"><summary>补充 / 更正明确事实</summary><Supplement key={task.id + ':' + task.version} busy={busy} onSubmit={(key, value, userRequest) => void run(async () => { const updated = await api.supplement(task.id, task.version, {[key]:{value,userRequest}}); setTask(updated); setTurn(null); setMessages(old => [...old,{role:'notice',text:'明确事实已记录，发票未保存。请重新查询当前数据后审核。'}]); setTasks(await api.tasks()) })}/></details>}
        </>}
      </>}
      {page === 'review' && task && <section className="content-page review"><div className="page-heading"><div><span className="eyebrow">HUMAN IN THE LOOP</span><h1 ref={heading} tabIndex={-1}>审核与结果</h1><p className="muted">Agent 建议不等于授权。每一项修改，由你决定。</p></div><Button onClick={() => navigate('workspace')}>返回对话</Button></div>
        <VerificationPanel turn={turn}/>
        <Review key={`${task.id}:${task.version}`} invoices={invoices} enterprise={session?.enterprise ?? ''} disabled={busy || session?.profile !== 'operator' || !!operation || task.status === 'COMPLETED' || task.status === 'CANCELLED'} onApprove={approval => void send(`现在保存以下经我逐项核对的精确修改，并查询发票核对：${pretty({enterprise:session?.enterprise,invoiceId:approval.invoiceId,changes:approval.changes})}`, approval)}/>
      </section>}
      {page === 'files' && <FilesPage heading={heading} selected={selected} canUpload={session?.profile === 'operator'} enterprise={session?.enterprise ?? ''} busy={busy}
        files={files} upload={upload} detail={fileDetail} onUpload={file => void uploadFile(file)} onRefresh={() => void refreshFiles()} onOpen={id => void openFile(id)} onToggle={file => void toggleKnowledge(file)}/>}
      {page === 'agents' && <AgentsPage heading={heading} selected={selected} canEdit={session?.profile === 'operator'} enterprise={session?.enterprise ?? ''} busy={busy}
        view={agentsView} skills={capabilities?.skills ?? []} onRefresh={() => void refreshAgents()} onSave={async (id, spec) => { let saved = false; await run(async () => { await saveAgent(id, spec); saved = true }); return saved }} onToggle={agent => void toggleAgent(agent)}
        onHistory={id => api.agentHistory(id)}/>}
      {page === 'capabilities' && <CapabilitiesPage heading={heading} selected={selected} canEdit={session?.profile === 'operator'} busy={busy} view={capabilities}
        onRefresh={() => void refreshCapabilities()} onSwitch={setPendingSwitch}
        onSave={async (id, spec) => { let saved = false; await run(async () => { await saveSkill(id, spec); saved = true }); return saved }}
        onHistory={id => api.skillHistory(id)} onOpenFiles={() => navigate('files')}/>}
      {page === 'audit' && <section className="content-page"><span className="eyebrow">TRACEABLE BY DESIGN</span><h1 ref={heading} tabIndex={-1}>审计记录</h1><p className="muted">仅当前用户与企业的真实元数据；不展示财务原文。</p><details onToggle={e => { if (e.currentTarget.open && audit === null && selected && !busy) void showAudit() }}><summary>展开真实审计记录</summary><Button disabled={!selected || busy} onClick={() => void showAudit()}>刷新审计</Button><Audit events={audit}/></details></section>}
    </main>
    <footer>受约束执行 <span>·</span> 可回读核验 <span>·</span> 模型文字不是财务执行证据</footer>
    <Modal open={cancelCandidate !== null} onOpenChange={open => { if (!open && !busy) setCancelCandidate(null) }} title="确认结束这项任务？" description="只有你明确确认后才会结束旧任务；不会自动创建新任务或执行财务写入。">
      {cancelCandidate && <div className="cancel-task-detail"><p><strong>{cancelCandidate.goal}</strong></p><small>{cancelCandidate.id.slice(0,8)} · {statusLabel[cancelCandidate.status]}</small><p>结束后任务不能继续执行，但历史记录和审计不会被清除。已发生的财务写入不会撤销。</p>
        {operation && <p role="alert">仍有待核验的财务操作，禁止结束任务。请先核查 operationId。</p>}
        <div className="open-task-actions"><Button disabled={busy} onClick={() => setCancelCandidate(null)}>返回</Button><Button variant="primary" disabled={busy || !!operation} onClick={() => void cancelOpenTask()}>确认结束旧任务</Button></div>
      </div>}
    </Modal>
    <Modal open={pendingSwitch !== null} onOpenChange={open => { if (!open && !busy) setPendingSwitch(null) }} title={pendingSwitch?.enabling ? '确认启用？' : '确认停用？'} description="这是全局开关，影响所有企业和使用它的 Agent。">
      {pendingSwitch && <div className="cancel-task-detail"><p>{impactMessage(pendingSwitch.kind, pendingSwitch.name, pendingSwitch.usedBy, pendingSwitch.enabling)}</p>
        <div className="open-task-actions"><Button disabled={busy} onClick={() => setPendingSwitch(null)}>返回</Button><Button variant="primary" disabled={busy} onClick={() => void confirmSwitch()}>{pendingSwitch.enabling ? '确认启用' : '确认停用'}</Button></div></div>}
    </Modal>
    <Modal open={identityOpen} onOpenChange={setIdentityOpen} title="选择工作空间" description="本地模拟身份，不是生产认证。权限由后端逐次校验。">
      {error && <p role="alert" className="error-banner">{error}</p>}
      <Identity busy={busy} session={session} onSelect={(profile, enterprise) => void run(async () => { const s = await api.select(profile, enterprise); reset(); setSession(s); setTasks(await api.tasks()); setIdentityOpen(false) })}/>
      {selected && <Button disabled={busy} onClick={() => void run(async () => { const s = await api.logout(); reset(); setSession(s); setIdentityOpen(false) })}>退出本地会话</Button>}
    </Modal>
    <Modal open={preferencesOpen} onOpenChange={setPreferencesOpen} title="长期记忆" description="只记录你明确提供的内容，不会从模型文本自动提取。同名时以最新一次为准；同名不同范围时，范围越具体越优先（仅我 · 当前企业 > 个人 > 企业共享）。删除或过期后，存储与历史模型输入中的内容一并清除，不可恢复。">
      <div className="stack">
        {memories.length === 0 ? <p className="muted">暂无长期记忆。</p> :
          memories.map(memory => <div key={`${memory.scope}:${memory.name}`} className={`memory-row ${memory.effective ? '' : 'overridden'}`}>
            <p><span className="rule-chip">{SCOPE_LABEL[memory.scope]}</span> <strong>{memory.name}</strong>：{memory.value}</p>
            <small className="muted">原话：{memory.userRequest}{memory.expiresOn ? ` · 有效至 ${memory.expiresOn}` : ''}{memory.effective ? '' : ' · 被更具体范围的同名记忆覆盖，当前不提供给模型'}</small>
            {(memory.scope !== 'ENTERPRISE' || session?.profile === 'operator') && <Button variant="ghost" disabled={busy} onClick={() => void removeMemory(memory)}>删除</Button>}
          </div>)}
        <form className="stack" onSubmit={savePreference}>
          <label>范围<select value={prefScope} onChange={e => setPrefScope(e.target.value as MemoryScope)}>
            <option value="USER_ENTERPRISE">仅我 · 当前企业（{session?.enterprise}）</option>
            <option value="PERSONAL">个人 · 所有企业（不能含企业、发票或流水编号）</option>
            {session?.profile === 'operator' && <option value="ENTERPRISE">企业共享（{session?.enterprise} 的所有成员可见）</option>}
          </select></label>
          <label>名称<input value={prefName} onChange={e => setPrefName(e.target.value)} maxLength={80} placeholder="例如 style"/></label>
          <label>明确值<input value={prefValue} onChange={e => setPrefValue(e.target.value)} maxLength={500} placeholder="例如 简洁"/></label>
          <label>你的原话<textarea value={prefRequest} onChange={e => setPrefRequest(e.target.value)} maxLength={1000} placeholder="例如：以后回答尽量简洁"/></label>
          <label>有效期（可选，到期后自动清除）<input type="date" value={prefExpiry} onChange={e => setPrefExpiry(e.target.value)}/></label>
          {/* Next to the button the user just pressed; the page banner is hidden behind this dialog. */}
          {error && <p role="alert" className="error-banner">{error}</p>}
          <Button type="submit" variant="primary" disabled={busy}>保存 / 更正</Button>
        </form>
      </div>
    </Modal>
    <Modal open={inspector} onOpenChange={setInspector} title="任务检查器" description="计划、实际执行与审计，按需展开。" drawer>
      {task && <><h3>任务计划 / 待办</h3><p>{task.goal}</p>{task.pending.length ? <ul>{task.pending.map(p => <li key={p}>{p}</li>)}</ul> : <p className="muted">没有结构化待办。当前 API 未提供实时计划事件。</p>}{task.awaitingSave?.length ? <><p>已声明、尚未保存（需在审核页逐项确认）：</p><ul>{task.awaitingSave.map(field => <li key={field}><code>{field}</code>{task.supplied[field] ? ` = ${task.supplied[field].value}` : ''}</li>)}</ul></> : null}<p className="muted">状态：{statusLabel[task.status]} · 版本 {task.version}</p><ExecutionPlan task={task} history={history} verification={verification}/><ContextSources task={task} history={history} preferences={preferences} enterprise={session?.enterprise ?? ''} role={session?.profile ?? ''}/><Compression modelInput={turn?.modelInput}/><details><summary>历史工具摘要（不是当前执行证据）</summary><pre>{pretty(task.recentTools)}</pre></details><details><summary>本轮模型输入（记忆投影）</summary><pre>{turn?.modelInput ?? '无'}</pre></details><details><summary>完整模型输入（系统提示 + 用户消息 + 工具 schema）</summary>{turn?.promptTrace ? <pre>{pretty(turn.promptTrace)}</pre>
          : savedTrace ? <><p className="hint">已持久化的本轮运行记录（{savedTrace.time}）；工具只保存名称，schema 原文不落库。</p><pre>{pretty({systemPrompt: savedTrace.systemPrompt, userMessage: savedTrace.userMessage, tools: savedTrace.toolNames, historySize: savedTrace.historySize, memorySource: savedTrace.memorySource, outcome: savedTrace.outcome})}</pre></>
          : <pre>无</pre>}</details><ToolGroup history={history}/><details><summary>任务原始数据（可能包含敏感字段）</summary><pre>{pretty(task)}</pre></details></>}
      <details onToggle={e => { if (e.currentTarget.open && audit === null && selected && !busy) void showAudit() }}><summary>真实审计</summary><Button disabled={!selected || busy} onClick={() => void showAudit()}>刷新审计</Button><Audit events={audit}/></details>
    </Modal>
  </div>
}

const planStateLabel: Record<PlanStep['state'], string> = {
  done: '已完成', current: '进行中', waiting: '等待用户', optional: '本次不需要'
}

function ExecutionPlan({task,history,verification}: {task:Task; history:Exchange[]; verification:TurnView['verification']}) {
  const steps = task.plan?.steps?.length ? task.plan.steps : deriveExecutionPlan(task,history,verification)
  return <section className="context-block execution-plan" aria-label="执行计划">
    <h3>执行计划</h3>
    <p className="hint">由后端根据持久化任务状态与真实工具历史推导并随每轮更新；模型文字不能替代保存核验。</p>
    <ol className="plan-list">{steps.map((step, index) => <li key={step.key} data-state={step.state}>
      <span className="plan-index">{index + 1}</span>
      <div><strong>{step.title}</strong><p>{step.detail}</p></div>
      <span className="plan-state">{planStateLabel[step.state]}</span>
    </li>)}</ol>
  </section>
}

function ContextSources({task,history,preferences,enterprise,role}: {
  task:Task; history:Exchange[]; preferences:Record<string, Statement>; enterprise:string; role:string
}) {
  const rules = citedRules(history)
  const knowledge = citedKnowledge(history)
  return <section className="context-sources" aria-label="上下文来源">
    <h3>上下文来源（分开查看）</h3>
    <div className="context-grid">
      <section><h4>任务进度</h4><p>{statusLabel[task.status]} · 版本 {task.version}</p>
        <p>待补：{task.pending.length ? task.pending.join('、') : '无'}</p>
        <p>待保存：{task.awaitingSave?.length ? task.awaitingSave.join('、') : '无'}</p>
        <p>最近工具：{task.recentTools.length ? task.recentTools.map(tool => `${tool.tool}(${tool.outcome})`).join('、') : '无'}</p></section>
      <section><h4>长期偏好</h4>{Object.keys(preferences).length
        ? <ul>{Object.entries(preferences).map(([name, statement]) => <li key={name}><code>{name}</code> = {statement.value}</li>)}</ul>
        : <p>暂无，或尚未刷新。</p>}</section>
      <section><h4>规则</h4>{rules.length
        ? <div className="rule-chips">{rules.map(rule => <span className="rule-chip" key={`${rule.id}@${rule.version}`}>{rule.id}@{rule.version}</span>)}</div>
        : <p>本轮尚未调用 query_rules；模型引用不单独算证据。</p>}
        <p className="hint">来自本轮 query_rules 工具结果。</p></section>
      <section><h4>知识检索</h4>{knowledge.length
        ? <ul>{knowledge.map(k => <li key={`${k.kind}:${k.name}@${k.version}:${k.location}`}><code>{k.name} {k.version}</code> · {k.location}</li>)}</ul>
        : <p>本轮尚未调用 search_knowledge，或没有命中。</p>}
        <p className="hint">来自本轮 search_knowledge 工具结果（只含启用规则与文件最新版本）。</p></section>
      <section><h4>企业上下文</h4><p>{enterprise || '未选择'} · {role === 'operator' ? 'Operator' : role === 'viewer' ? 'Viewer' : '未选择'}</p>
        <p>{task.goal}</p></section>
    </div>
  </section>
}

function Compression({modelInput}: {modelInput: string | null | undefined}) {
  const summary = contextSummary(modelInput)
  if (!summary) return null
  return <section className="context-block" aria-label="上下文压缩">
    <h3>上下文压缩</h3>
    <p className="hint">最近几轮原文直接提供；更早的轮次只保留你的限制与更正和关键对象，原文可由 Agent 用 recall_context 取回。</p>
    <p>已对话 {summary.turnsTotal} 轮 · 折叠 {summary.omittedTurns} 轮{summary.latestMonth && <> · 当前月份「{summary.latestMonth}」（第 {summary.latestMonthTurn} 轮）</>}</p>
    {summary.constraints.length > 0 && <ul>{summary.constraints.map(c => <li key={c.turn}>第 {c.turn} 轮：{c.user}</li>)}</ul>}
    {summary.ids.length > 0 && <p className="muted">提及的编号：{summary.ids.join('、')}</p>}
  </section>
}

function TerminalTaskPanel({task,onCreateNew}: {task:Task; onCreateNew:()=>void}) {
  const completed = task.status === 'COMPLETED'
  return <section className="terminal-task" aria-labelledby="terminal-task-title">
    <div className="terminal-icon"><ShieldCheck size={20}/></div>
    <span className="eyebrow">HISTORICAL TASK</span>
    <h2 id="terminal-task-title">{completed ? '这项任务已经结束' : '这项任务已取消'}</h2>
    <p>已恢复任务基本信息。历史工具记录仅供追溯，不代表当前数据库状态，也不会恢复任何写入授权。</p>
    <div className="terminal-meta"><span>{task.id.slice(0,8)}</span><span>·</span><span>{statusLabel[task.status]}</span></div>
    <Button variant="primary" onClick={onCreateNew}><Sparkles size={16}/> 基于原任务新建查询 <ArrowUpRight size={15}/></Button>
    <small>将带入原任务目标，并创建独立的新任务编号。</small>
  </section>
}

function Identity({busy,session,onSelect}: {busy:boolean; session:Session|null; onSelect:(p:string,e:string)=>void}) {
  const [profile,setProfile] = useState(session?.profile || 'operator'), [enterprise,setEnterprise] = useState(session?.enterprise || 'C001')
  return <form className="stack" onSubmit={e => { e.preventDefault(); onSelect(profile,enterprise) }}><label>本地角色<select value={profile} onChange={e => setProfile(e.target.value)}><option value="operator">操作员 · U001</option><option value="viewer">只读成员 · U002</option></select></label><label>企业<select value={enterprise} onChange={e => setEnterprise(e.target.value)}><option value="C001">C001 · 甲企业</option><option value="C002">C002 · 乙企业</option></select></label><p className="muted">U002 对 C001 只读，无权访问 C002。</p><Button variant="primary" disabled={busy} type="submit">进入工作空间</Button></form>
}
function Thread({messages,busy,streaming,progress}: {messages:Message[]; busy:boolean; streaming:string; progress:string}) {
  const bottom = useRef<HTMLDivElement>(null)
  useEffect(() => { bottom.current?.scrollIntoView({block:'nearest'}) }, [messages, busy, streaming, progress])
  return <section className="thread" aria-label="任务对话"><div className="thread-inner" role="log" aria-live="polite">{messages.map((m,i) => <article key={i} className={`message ${m.role}`}><div className="message-label">{m.role === 'agent' ? <><Sparkles size={15}/>财税 Agent <span>模型生成 · 不代表保存成功</span></> : m.role === 'user' ? '你' : <><ShieldCheck size={14}/>工作空间</>}</div><p>{m.text}</p>{m.history && <ToolGroup history={m.history}/>}</article>)}{streaming && <article className="message agent"><div className="message-label"><Sparkles size={15}/>财税 Agent <span>模型生成 · 不代表保存成功</span></div><p>{streaming}</p></article>}{busy && <p className="request-state" role="status"><CircleDashed size={16}/>{progress || '请求处理中，等待后端完整响应…'}</p>}<div ref={bottom}/></div></section>
}
function Composer({disabled,onSend}: {disabled:boolean; onSend:(m:string)=>void}) {
  const [message,setMessage] = useState('')
  function sendNow() { if (!disabled && message.trim()) { onSend(message.trim()); setMessage('') } }
  function submit(e: FormEvent) { e.preventDefault(); sendNow() }
  function keydown(e: KeyboardEvent<HTMLTextAreaElement>) {
    // Enter sends; Shift+Enter inserts a newline. Never send while an IME composition is open.
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); sendNow() }
  }
  return <form className="composer" onSubmit={submit}><label className="sr-only" htmlFor="message">向 Agent 发送消息</label><textarea id="message" value={message} onChange={e => setMessage(e.target.value)} disabled={disabled} placeholder="补充问题，或让 Agent 查询当前数据库…" maxLength={8000} onKeyDown={keydown}/><div className="composer-bottom"><span><ShieldCheck size={13}/> 发送消息不会授予保存权限 <span className="keyboard-hint">· Enter 发送，Shift + Enter 换行</span></span><Button type="submit" variant="primary" disabled={disabled || !message.trim()} aria-label="发送消息"><ArrowUp size={18}/></Button></div></form>
}
function ToolGroup({history}: {history:Exchange[]}) {
  if (!history.length) return null
  return <details className="tool-group"><summary>实际执行活动 · {history.length} 个工具调用</summary><ol>{history.map((exchange,i) => { const result = parseTool(exchange); return <li key={`${exchange.call.id}-${i}`}><details><summary><span className="tool-dot"/>{exchange.call.name} <span className="muted">{!result ? '结果无法解析' : result.error ? `错误 ${result.error.code}` : '已返回'}</span></summary><p className="muted">真实调用 {exchange.call.id} · 原始数据可能含金额/敏感字段；金额单位为分。</p><pre>{pretty({call:exchange.call,result:result ?? exchange.resultJson})}</pre></details></li> })}</ol></details>
}
function Supplement({busy,onSubmit}: {busy:boolean; onSubmit:(key:string,value:string,quote:string)=>void}) {
  const [key,setKey] = useState(''), [value,setValue] = useState(''), [quote,setQuote] = useState('')
  return <form className="stack" onSubmit={e => { e.preventDefault(); onSubmit(key,value,quote) }}><p className="muted">只记录你明确提供的事实，不写入发票，也不授权保存。</p><label>字段<input value={key} onChange={e => setKey(e.target.value)} required placeholder="F002.purpose"/></label><label>明确值<input value={value} onChange={e => setValue(e.target.value)} required/></label><label>用户原话<textarea value={quote} onChange={e => setQuote(e.target.value)} required/></label><Button type="submit" disabled={busy}>仅记录信息</Button></form>
}
function VerificationPanel({turn}: {turn:TurnView|null}) {
  const verification = turn?.verification
  return <section className={`verification ${verification?.verified ? 'verified' : ''}`} aria-label="保存核验状态"><ShieldCheck size={22}/><div><h2>{verification?.verified ? 'verified=true · 数据库回读已核验' : '尚无已核验的保存结果'}</h2><p>{turn?.message ?? '请先查询发票，再逐项审核修改。模型建议与任务结束状态不构成保存证据。'}</p>{turn?.operationId && <p>operationId: <code>{turn.operationId}</code></p>}{verification && <><p>{verification.key.enterpriseId} / {verification.key.invoiceId}</p><dl><dt>数据库用途</dt><dd>{verification.purpose || '（空）'}</dd><dt>数据库备注</dt><dd>{verification.remarks || '（空）'}</dd></dl></>}</div></section>
}
const toolLabel: Record<string, string> = {
  query_invoices: '查询发票', query_bank_entries: '查询银行流水', check_invoice_purpose: '检查用途缺口',
  query_rules: '查询规则', search_knowledge: '检索资料', recall_context: '回溯原文', update_invoice: '保存用途/备注（需逐项确认）',
}
const KNOWN_ENTERPRISES = ['C001', 'C002']

function AgentPicker({agents, value, onChange, disabled}: {agents: AgentDef[]; value: string; onChange: (id: string) => void; disabled: boolean}) {
  if (agents.length <= 1) return null
  return <label className="agent-picker"><Bot size={14}/><span>由谁处理</span>
    <select value={value} onChange={e => onChange(e.target.value)} disabled={disabled}>
      <option value="">默认 · 财税助手</option>
      {agents.filter(a => a.id !== 'finance-assistant').map(a => <option key={a.id} value={a.id}>{a.spec.name} · v{a.version}{a.spec.tools.includes('update_invoice') ? '' : ' · 只读'}</option>)}
    </select></label>
}

const emptySpec: AgentSpec = {name:'', responsibility:'', instructions:'', modelProfile:'default', model:null, tools:['query_invoices','query_bank_entries','check_invoice_purpose','query_rules','search_knowledge'], enterprises:[]}

function AgentForm({initial, editingId, profiles, skills, enterprise, busy, onSave, onCancel}: {
  initial: AgentSpec; editingId: string | null; profiles: AgentsView['profiles']; skills: SkillInfo[]; enterprise: string; busy: boolean
  onSave: (id: string, spec: AgentSpec) => Promise<boolean>; onCancel: () => void
}) {
  const [id, setId] = useState(editingId ?? '')
  const [spec, setSpec] = useState<AgentSpec>(initial.enterprises.length ? initial : {...initial, enterprises:[enterprise]})
  const toggle = (list: string[], item: string) => list.includes(item) ? list.filter(x => x !== item) : [...list, item]
  const problem = agentProblem(id, spec)
  return <form className="agent-form stack" onSubmit={e => { e.preventDefault(); void onSave(id, spec).then(saved => { if (saved) onCancel() }) }}>
    <h2>{editingId ? `编辑 ${editingId}（保存为新版本）` : '新建 Agent'}</h2>
    <label>Agent 编号<input value={id} onChange={e => setId(e.target.value.trim())} disabled={!!editingId} placeholder="例如 finance-reader" maxLength={40}/></label>
    <label>名称<input value={spec.name} onChange={e => setSpec({...spec, name:e.target.value})} maxLength={60} placeholder="例如 只读查询员"/></label>
    <label>职责<input value={spec.responsibility} onChange={e => setSpec({...spec, responsibility:e.target.value})} maxLength={300} placeholder="一句话说明它负责什么"/></label>
    <label>提示词（追加在系统安全规则之后，不能覆盖它们）<textarea value={spec.instructions} onChange={e => setSpec({...spec, instructions:e.target.value})} maxLength={4000} placeholder="例如：回答尽量简短，并注明规则版本。"/></label>
    <div className="agent-form-row">
      <label>模型配置<select value={spec.modelProfile} onChange={e => setSpec({...spec, modelProfile:e.target.value})}>
        {profiles.map(p => <option key={p.name} value={p.name}>{p.name}{p.configured ? (p.model ? ` · ${p.model}` : '') : ' · 未配置'}</option>)}
        {!profiles.some(p => p.name === spec.modelProfile) && <option value={spec.modelProfile}>{spec.modelProfile} · 未配置</option>}
      </select></label>
      <label>模型名（可选，覆盖该配置的模型）<input value={spec.model ?? ''} onChange={e => setSpec({...spec, model:e.target.value.trim() || null})} maxLength={80} placeholder="留空则用配置中的模型"/></label>
    </div>
    <fieldset><legend>能力（Agent 只能使用勾选的能力；用户自身权限仍逐次校验）</legend>
      {AGENT_TOOLS.map(tool => <label key={tool} className="check-label"><input type="checkbox" checked={spec.tools.includes(tool)} onChange={() => setSpec({...spec, tools:toggle(spec.tools, tool)})}/><span>{toolLabel[tool]} <code>{tool}</code></span></label>)}
    </fieldset>
    {skills.length > 0 && <fieldset><legend>技能（技能自带说明与所需查询工具；不能授予保存能力）</legend>
      {skills.map(({skill}) => <label key={skill.id} className="check-label"><input type="checkbox" checked={(spec.skills ?? []).includes(skill.id)} onChange={() => setSpec({...spec, skills:toggle(spec.skills ?? [], skill.id)})}/>
        <span>{skill.spec.name} <code>{skill.id} v{skill.version}</code>{!skill.enabled && ' · 已停用'} · {skill.spec.tools.map(t => toolLabel[t] ?? t).join('、')}</span></label>)}
    </fieldset>}
    <fieldset><legend>适用企业（只能选择你有修改权限的企业）</legend>
      {KNOWN_ENTERPRISES.map(e => <label key={e} className="check-label"><input type="checkbox" checked={spec.enterprises.includes(e)} onChange={() => setSpec({...spec, enterprises:toggle(spec.enterprises, e)})}/><span>{e}</span></label>)}
    </fieldset>
    {problem && <p className="hint">{problem}</p>}
    <div className="open-task-actions"><Button type="button" onClick={onCancel} disabled={busy}>取消</Button><Button type="submit" variant="primary" disabled={busy || !!problem}>{editingId ? '保存新版本' : '创建 Agent'}</Button></div>
  </form>
}

function AgentsPage({heading, selected, canEdit, enterprise, busy, view, skills, onRefresh, onSave, onToggle, onHistory}: {
  heading: RefObject<HTMLHeadingElement | null>; selected: boolean; canEdit: boolean; enterprise: string; busy: boolean; view: AgentsView | null; skills: SkillInfo[]
  onRefresh: () => void; onSave: (id: string, spec: AgentSpec) => Promise<boolean>; onToggle: (agent: AgentDef) => void; onHistory: (id: string) => Promise<AgentDef[]>
}) {
  const [editing, setEditing] = useState<{id: string | null; spec: AgentSpec} | null>(null)
  const [history, setHistory] = useState<{id: string; versions: AgentDef[]} | null>(null)
  return <section className="content-page agents-page">
    <div className="page-heading"><div><span className="eyebrow">AGENTS</span><h1 ref={heading} tabIndex={-1}>Agent 配置</h1>
      <p className="muted">每个 Agent 是一个职责：提示词、模型配置、能力与适用企业。修改会保存为新版本，从下一轮对话起生效；进行中的一轮不受影响。Agent 不能扩大用户权限。</p></div>
      <div className="open-task-actions"><Button disabled={!selected || busy} onClick={onRefresh}>刷新</Button>
        {canEdit && <Button variant="primary" disabled={!selected || busy || !view} onClick={() => setEditing({id:null, spec:emptySpec})}>新建 Agent</Button>}</div></div>
    {!selected ? <p className="empty">选择企业与身份后查看 Agent。</p> : !view ? <p className="muted">正在加载…</p> : <>
      {!canEdit && <p className="hint">只读成员可以查看 Agent，不能修改。</p>}
      {editing && <AgentForm key={editing.id ?? 'new'} initial={editing.spec} editingId={editing.id} profiles={view.profiles} skills={skills} enterprise={enterprise} busy={busy} onSave={onSave} onCancel={() => setEditing(null)}/>}
      <div className="agent-list">{view.agents.map(agent => <article key={agent.id} className={`agent-card ${agent.enabled ? '' : 'disabled'}`}>
        <header><div><h2>{agent.spec.name}</h2><small><code>{agent.id}</code> · v{agent.version} · {agent.enabled ? '启用' : '已停用'} · {agent.changedBy} {agent.changedAt.replace('T',' ').slice(0,16)}</small></div>
          {canEdit && <div className="open-task-actions"><Button variant="ghost" disabled={busy} onClick={() => setEditing({id:agent.id, spec:agent.spec})}>编辑</Button>
            <Button variant="ghost" disabled={busy} onClick={() => onToggle(agent)}>{agent.enabled ? '停用' : '启用'}</Button></div>}</header>
        <p>{agent.spec.responsibility}</p>
        <dl><dt>能力</dt><dd className="rule-chips">{agent.spec.tools.map(t => <span key={t} className={`rule-chip ${t === 'update_invoice' ? 'write' : ''}`}>{toolLabel[t] ?? t}</span>)}</dd>
          {(agent.spec.skills ?? []).length > 0 && <><dt>技能</dt><dd>{(agent.spec.skills ?? []).map(id => <span key={id} className="rule-chip">{id}{view.effective?.[agent.id]?.droppedSkills.includes(id) ? '（已停用，未生效）' : ''}</span>)}</dd></>}
          {view.effective?.[agent.id] && <><dt>实际可用</dt><dd className="rule-chips">{view.effective[agent.id].tools.length ? view.effective[agent.id].tools.map(t => <span key={t} className={`rule-chip ${t === 'update_invoice' ? 'write' : ''}`}>{toolLabel[t] ?? t}</span>) : <span className="muted">无（所需能力均已停用）</span>}
            {[...new Set([...agent.spec.tools, ...skills.filter(i => (agent.spec.skills ?? []).includes(i.skill.id) && i.skill.enabled).flatMap(i => i.skill.spec.tools)])]
              .filter(t => view.effective![agent.id].disabledTools.includes(t)).map(t => <span key={t} className="rule-chip off">{toolLabel[t] ?? t} · 已停用</span>)}</dd></>}
          <dt>企业</dt><dd>{agent.spec.enterprises.join('、')}{!agent.spec.enterprises.includes(enterprise) && <small className="muted">（不适用当前企业 {enterprise}）</small>}</dd>
          <dt>模型</dt><dd>{agent.spec.modelProfile}{agent.spec.model ? ` · ${agent.spec.model}` : ''}{view.profiles.find(p => p.name === agent.spec.modelProfile)?.configured === false && <small className="muted">（未配置）</small>}</dd>
          {agent.spec.instructions && <><dt>提示词</dt><dd className="prompt">{agent.spec.instructions}</dd></>}</dl>
        <Button variant="ghost" disabled={busy} onClick={() => void onHistory(agent.id).then(versions => setHistory({id:agent.id, versions}), () => {})}>版本历史</Button>
        {history?.id === agent.id && <ol className="agent-history">{history.versions.map(v => <li key={v.version}>v{v.version} · {v.enabled ? '启用' : '停用'} · {v.changedBy} · {v.changedAt.replace('T',' ').slice(0,19)} · {v.spec.tools.length} 项能力</li>)}</ol>}
      </article>)}</div>
    </>}
  </section>
}

const emptySkill: SkillSpec = {name:'', description:'', instructions:'', tools:['query_rules']}

function SkillForm({initial, editingId, busy, onSave, onCancel}: {initial: SkillSpec; editingId: string | null; busy: boolean; onSave: (id: string, spec: SkillSpec) => Promise<boolean>; onCancel: () => void}) {
  const [id, setId] = useState(editingId ?? '')
  const [spec, setSpec] = useState<SkillSpec>(initial)
  const problem = skillProblem(id, spec)
  const readTools = AGENT_TOOLS.filter(t => t !== 'update_invoice')
  return <form className="agent-form stack" onSubmit={e => { e.preventDefault(); void onSave(id, spec).then(saved => { if (saved) onCancel() }) }}>
    <h2>{editingId ? `编辑技能 ${editingId}（保存为新版本）` : '新建技能'}</h2>
    <label>技能编号<input value={id} onChange={e => setId(e.target.value.trim())} disabled={!!editingId} maxLength={40} placeholder="例如 month-summary"/></label>
    <label>名称<input value={spec.name} onChange={e => setSpec({...spec, name:e.target.value})} maxLength={60}/></label>
    <label>简介<input value={spec.description} onChange={e => setSpec({...spec, description:e.target.value})} maxLength={300}/></label>
    <label>说明（追加到使用它的 Agent 的提示词中）<textarea value={spec.instructions} onChange={e => setSpec({...spec, instructions:e.target.value})} maxLength={2000}/></label>
    <fieldset><legend>所需工具（只能是查询类工具）</legend>
      {readTools.map(tool => <label key={tool} className="check-label"><input type="checkbox" checked={spec.tools.includes(tool)} onChange={() => setSpec({...spec, tools: spec.tools.includes(tool) ? spec.tools.filter(t => t !== tool) : [...spec.tools, tool]})}/><span>{toolLabel[tool]} <code>{tool}</code></span></label>)}
    </fieldset>
    {problem && <p className="hint">{problem}</p>}
    <div className="open-task-actions"><Button type="button" onClick={onCancel} disabled={busy}>取消</Button><Button type="submit" variant="primary" disabled={busy || !!problem}>{editingId ? '保存新版本' : '创建技能'}</Button></div>
  </form>
}

function CapabilitiesPage({heading, selected, canEdit, busy, view, onRefresh, onSwitch, onSave, onHistory, onOpenFiles}: {
  heading: RefObject<HTMLHeadingElement | null>; selected: boolean; canEdit: boolean; busy: boolean; view: Capabilities | null
  onRefresh: () => void; onSwitch: (change: {kind: '工具' | '技能'; name: string; usedBy: string[]; enabling: boolean}) => void
  onSave: (id: string, spec: SkillSpec) => Promise<boolean>; onHistory: (id: string) => Promise<Skill[]>; onOpenFiles: () => void
}) {
  const [editing, setEditing] = useState<{id: string | null; spec: SkillSpec} | null>(null)
  const [history, setHistory] = useState<{id: string; versions: Skill[]} | null>(null)
  const switchTool = (t: ToolInfo) => onSwitch({kind:'工具', name:t.name, usedBy:t.usedBy, enabling:!t.enabled})
  return <section className="content-page agents-page capabilities-page">
    <div className="page-heading"><div><span className="eyebrow">CAPABILITIES</span><h1 ref={heading} tabIndex={-1}>能力中心</h1>
      <p className="muted">工具、技能与知识分开登记、各自有版本与启停，可被多个 Agent 复用。停用对所有 Agent 生效，从下一轮对话起不再提供给模型；未授予的能力与已停用的能力返回不同错误码。</p></div>
      <Button disabled={!selected || busy} onClick={onRefresh}>刷新</Button></div>
    {!selected ? <p className="empty">选择企业与身份后查看。</p> : !view ? <p className="muted">正在加载…</p> : <>
      {!canEdit && <p className="hint">只读成员可以查看，不能修改。全局开关与技能修改需要对所有企业有修改权限。</p>}
      <section className="file-list" aria-label="工具"><h2>工具</h2>
        <table><thead><tr><th>工具</th><th>版本</th><th>状态</th><th>使用它的 Agent</th><th></th></tr></thead>
          <tbody>{view.tools.map(t => <tr key={t.name}><td>{toolLabel[t.name] ?? t.name}<small><code>{t.name}</code> · {t.description}</small></td><td>{t.version}</td>
            <td><span className={`knowledge-state ${t.enabled ? 'on' : 'off'}`}>{t.enabled ? '启用' : '已停用'}</span>{t.changedBy && <small>{t.changedBy} {t.changedAt?.replace('T',' ').slice(0,16)}</small>}</td>
            <td>{t.usedBy.length ? t.usedBy.join('、') : <span className="muted">无</span>}</td>
            <td>{canEdit && <Button variant="ghost" disabled={busy} onClick={() => switchTool(t)}>{t.enabled ? '停用' : '启用'}</Button>}</td></tr>)}</tbody></table>
      </section>
      <section aria-label="技能" className="skills-section"><div className="section-heading"><h2>技能</h2>{canEdit && <Button variant="primary" disabled={busy} onClick={() => setEditing({id:null, spec:emptySkill})}>新建技能</Button>}</div>
        {editing && <SkillForm key={editing.id ?? 'new'} initial={editing.spec} editingId={editing.id} busy={busy} onSave={onSave} onCancel={() => setEditing(null)}/>}
        <div className="agent-list">{view.skills.map(({skill, usedBy}) => <article key={skill.id} className={`agent-card ${skill.enabled ? '' : 'disabled'}`}>
          <header><div><h2>{skill.spec.name}</h2><small><code>{skill.id}</code> · v{skill.version} · {skill.enabled ? '启用' : '已停用'} · {skill.changedBy} {skill.changedAt.replace('T',' ').slice(0,16)}</small></div>
            {canEdit && <div className="open-task-actions"><Button variant="ghost" disabled={busy} onClick={() => setEditing({id:skill.id, spec:skill.spec})}>编辑</Button>
              <Button variant="ghost" disabled={busy} onClick={() => onSwitch({kind:'技能', name:skill.id, usedBy, enabling:!skill.enabled})}>{skill.enabled ? '停用' : '启用'}</Button></div>}</header>
          {skill.spec.description && <p>{skill.spec.description}</p>}
          <dl><dt>工具</dt><dd className="rule-chips">{skill.spec.tools.map(t => <span key={t} className="rule-chip">{toolLabel[t] ?? t}</span>)}</dd>
            <dt>使用者</dt><dd>{usedBy.length ? usedBy.join('、') : <span className="muted">尚无 Agent 使用</span>}</dd>
            <dt>说明</dt><dd className="prompt">{skill.spec.instructions}</dd></dl>
          <Button variant="ghost" disabled={busy} onClick={() => void onHistory(skill.id).then(versions => setHistory({id:skill.id, versions}), () => {})}>版本历史</Button>
          {history?.id === skill.id && <ol className="agent-history">{history.versions.map(v => <li key={v.version}>v{v.version} · {v.enabled ? '启用' : '停用'} · {v.changedBy} · {v.changedAt.replace('T',' ').slice(0,19)} · {v.spec.tools.join('、')}</li>)}</ol>}
        </article>)}</div>
      </section>
      <section aria-label="知识" className="skills-section"><h2>知识</h2>
        <p>规则按版本与启用状态管理（命令行 <code>--rules-import / --rules-update / --rules-disable</code>，更新即新增版本）；上传的 Word、PDF、Excel 在“资料文件”中按文件启停，只检索每个文件的最新版本。<code>search_knowledge</code> 只使用启用且生效的规则与启用文件。</p>
        <Button onClick={onOpenFiles}>打开资料文件</Button>
      </section>
    </>}
  </section>
}

const kindLabel: Record<StoredFile['kind'], string> = {XLSX:'Excel', XLS:'Excel（旧版）', DOCX:'Word', PDF:'PDF'}
const statusText: Record<UploadResult['status'], string> = {IMPORTED:'已导入', DUPLICATE:'重复内容，未新增', REJECTED:'未导入'}
function size(bytes: number) { return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB` }

function IssueList({title, issues}: {title: string; issues: RowIssue[]}) {
  if (!issues.length) return null
  return <details className="issue-list" open={issues.length <= 5}><summary>{title} · {issues.length}</summary>
    <ul>{issues.map((issue, i) => <li key={i}><code>{issueLocation(issue)}</code>{issueReason(issue.reason)}</li>)}</ul></details>
}

function FilesPage({heading, selected, canUpload, enterprise, busy, files, upload, detail, onUpload, onRefresh, onOpen, onToggle}: {
  heading: RefObject<HTMLHeadingElement | null>; selected: boolean; canUpload: boolean; enterprise: string; busy: boolean
  files: StoredFile[] | null; upload: UploadResult | null; detail: FileDetail | null
  onUpload: (file: File) => void; onRefresh: () => void; onOpen: (id: string) => void; onToggle: (file: StoredFile) => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const [chosen, setChosen] = useState<File | null>(null)
  function submit(event: FormEvent) {
    event.preventDefault()
    if (!chosen) return
    onUpload(chosen); setChosen(null)
    if (input.current) input.current.value = ''
  }
  return <section className="content-page files-page">
    <div className="page-heading"><div><span className="eyebrow">SOURCE FILES</span><h1 ref={heading} tabIndex={-1}>资料文件</h1>
      <p className="muted">上传 Excel、Word 或文字型 PDF。每个文件记录上传者、版本与内容指纹；相同内容不会重复保存，已有记录不会被覆盖。</p></div>
      <Button disabled={!selected || busy} onClick={onRefresh}>刷新列表</Button></div>
    {!selected ? <p className="empty">选择企业与身份后，查看和上传该企业的资料。</p> : <>
      <form className="upload-form" onSubmit={submit}>
        <label className="upload-drop"><FileUp size={20}/><span><strong>{chosen ? chosen.name : '选择要上传的文件'}</strong>
          <small>{chosen ? size(chosen.size) : `.xlsx / .xls / .docx / .pdf，最大 10 MB · 归属 ${enterprise}`}</small></span>
          <input ref={input} type="file" accept={UPLOAD_ACCEPT} disabled={!canUpload || busy} onChange={e => setChosen(e.target.files?.[0] ?? null)}/></label>
        <Button type="submit" variant="primary" disabled={!canUpload || busy || !chosen}>上传并解析</Button>
      </form>
      {!canUpload && <p className="hint">只读成员可以查看资料，不能上传。上传需要对 {enterprise} 有修改权限。</p>}
      {upload && <section className={`upload-result ${upload.status.toLowerCase()}`} role="status" aria-label="上传结果">
        <h2>{statusText[upload.status]}</h2><p>{describeUpload(upload)}</p>
        <IssueList title="需要修正的问题（整个文件未保存）" issues={upload.errors}/>
        <IssueList title="同编号已有记录，未覆盖" issues={upload.conflicts}/>
        <IssueList title="属于其他企业，未导入" issues={upload.skipped}/>
        {(upload.conflicts.length >= 200 || upload.skipped.length >= 200 || upload.errors.length >= 200) && <p className="hint">只显示前 200 条。</p>}
      </section>}
      <section className="file-list" aria-label="已上传文件">
        <h2>{enterprise} 的文件</h2>
        {files === null ? <p className="muted">正在加载…</p> : files.length === 0 ? <p className="empty">还没有上传文件。</p> :
          <table><thead><tr><th>文件</th><th>版本</th><th>类型</th><th>大小</th><th>上传</th><th>作为知识</th><th></th></tr></thead>
            <tbody>{files.map(f => <tr key={f.id}><td>{f.name}</td><td>v{f.version}{!f.latest && <small>已被新版本取代</small>}</td><td>{kindLabel[f.kind]}</td><td>{size(f.sizeBytes)}</td>
              <td>{f.uploadedBy}<small>{f.uploadedAt.replace('T', ' ').slice(0, 19)}</small></td>
              <td><span className={`knowledge-state ${f.latest && f.knowledgeEnabled ? 'on' : 'off'}`}>{!f.latest ? '不检索（旧版本）' : f.knowledgeEnabled ? '检索中' : '已停用'}</span>
                {f.latest && canUpload && <Button variant="ghost" disabled={busy} onClick={() => onToggle(f)}>{f.knowledgeEnabled ? '停用' : '启用'}</Button>}</td>
              <td><Button variant="ghost" disabled={busy} onClick={() => onOpen(f.id)}>查看内容</Button></td></tr>)}</tbody></table>}
        <p className="hint">Agent 只检索每个文件的最新版本，且仅限“检索中”的文件；旧版本与已停用文件保留可查，但不会作为回答依据。</p>
      </section>
      {detail && <section className="file-detail" aria-label="文件内容">
        <h2>{detail.file.name} · v{detail.file.version}</h2>
        <p className="hint">按原文位置分段保存，回答可据此引用来源。SHA-256 <code>{detail.file.sha256.slice(0, 16)}…</code></p>
        <ol>{detail.chunks.slice(0, 300).map(c => <li key={c.seq}><code>{c.location}</code><span>{c.text}</span></li>)}</ol>
        {detail.chunks.length > 300 && <p className="hint">共 {detail.chunks.length} 段，此处只显示前 300 段。</p>}
      </section>}
    </>}
  </section>
}
function Audit({events}: {events:AuditEvent[]|null}) {
  return <div className="audit-list">{events === null ? <p className="muted">展开后按需加载；未选择身份时无法读取。</p> : events.length ? events.map(e => <details key={e.id}><summary><span>{e.tool ?? '请求'} · {e.outcome}</span><small>{e.time}</small>{e.verified && <Check size={15}/>}</summary><dl>{Object.entries(e).filter(([key,value]) => value !== null && !['id','tool','outcome','time'].includes(key)).map(([key,value]) => <div key={key}><dt>{key}</dt><dd>{String(value)}</dd></div>)}</dl></details>) : <p className="empty">暂无审计事件。</p>}</div>
}

export function Review({invoices,enterprise,disabled,onApprove}: {invoices:Invoice[]; enterprise:string; disabled:boolean; onApprove:(a:Approval)=>void}) {
  const [invoiceId,setInvoiceId] = useState(''), [field,setField] = useState<'purpose'|'remarks'>('purpose'), [value,setValue] = useState('')
  const [checked,setChecked] = useState(false), [draft,setDraft] = useState<Approval|null>(null)
  const invoice = invoices.find(i => i.key.invoice_id === invoiceId)
  const current = invoice?.[field]
  const valid = !!invoice && current !== value && checked && !disabled
  const fieldLabel = field === 'purpose' ? '用途 purpose' : '备注 remarks'
  const change = (work:()=>void) => { work(); setChecked(false); setDraft(null) }
  const row = (label:string, content:ReactNode) => <div className="review-row"><span>{label}</span><div>{content}</div></div>
  return <section className="review-editor"><h2>核对精确修改</h2><p className="muted">仅支持用途与备注。空白新值表示明确清空。恢复或新一轮请求后，请重新查询。</p>{!invoices.length && <p className="empty">本轮没有发票查询证据。返回对话，先让 Agent 查询当前企业的发票。</p>}
    {row('企业 / 发票', <label><span className="sr-only">选择待审核发票</span><select value={invoiceId} onChange={e => change(() => setInvoiceId(e.target.value))}><option value="">选择本轮已查询发票</option>{invoices.map(i => <option key={i.key.invoice_id} value={i.key.invoice_id}>{enterprise} / {i.key.invoice_id} · {i.invoice_date} · {i.seller}</option>)}</select></label>)}
    {row('修改字段', <label><span className="sr-only">修改字段</span><select value={field} onChange={e => change(() => setField(e.target.value as 'purpose'|'remarks'))}><option value="purpose">用途 purpose</option><option value="remarks">备注 remarks</option></select></label>)}
    {row('数据库当前值', <p className="current-value">{invoice ? current || '（空）' : '尚无已查询记录'}</p>)}
    {row('拟修改值', <label><span className="sr-only">精确新值，空白表示清空</span><textarea value={value} onChange={e => change(() => setValue(e.target.value))} placeholder="输入你明确要求保存的值" maxLength={4000}/></label>)}
    <label className="check-label"><input type="checkbox" checked={checked} disabled={!invoice || disabled} onChange={e => setChecked(e.target.checked)}/><span>我已核对 {enterprise} / {invoiceId || '发票'} 的{fieldLabel}、数据库当前值与拟修改值。</span></label>
    <div className="review-actions"><p className="muted">每次确认只绑定一张发票的一个精确字段。</p><Button variant="primary" disabled={!valid} onClick={() => setDraft({operationId:crypto.randomUUID(),invoiceId,changes:{[field]:value},confirmed:true})}>继续二次确认<ChevronRight size={16}/></Button></div>
    <Modal open={draft !== null} onOpenChange={open => { if (!open) setDraft(null) }} title="确认现在保存这项修改？" description="点击下方按钮将授予本轮精确写入权限。Agent 仍需实际调用工具，结果以 verified 与数据库回读为准。">
      {draft && <><dl className="confirmation-values"><dt>企业 / 发票</dt><dd>{enterprise} / {draft.invoiceId}</dd><dt>字段</dt><dd>{fieldLabel}</dd><dt>数据库当前值</dt><dd>{current || '（空）'}</dd><dt>拟修改值</dt><dd>{value || '（明确清空）'}</dd><dt>operationId</dt><dd><code>{draft.operationId}</code></dd></dl><p className="muted">未知结果不自动重试。此授权不会随任务恢复。</p><div className="dialog-actions"><Button onClick={() => setDraft(null)}>返回核对</Button><Button variant="primary" disabled={!valid} onClick={() => { const exact = draft; setDraft(null); setChecked(false); onApprove(exact) }}>确认，现在保存{!value && ' / 清空'}</Button></div></>}
    </Modal>
  </section>
}
