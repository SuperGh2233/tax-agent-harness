import { useEffect, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { ArrowUp, ArrowUpRight, Check, ChevronRight, CircleDashed, FileCheck2, History, PanelRight, ShieldCheck, Sparkles } from 'lucide-react'
import { ApiError, createAndRunFirstTurn, DemoApi, findOpenTask, friendlyError, isTerminalTaskStatus, parseTool, queriedInvoices, verifiedWriteResult } from './api'
import type { Approval, AuditEvent, Exchange, Invoice, Session, Task, TurnView } from './api'
import { Button, Modal } from './ui'

const api = new DemoApi()
type Page = 'home' | 'workspace' | 'review' | 'history' | 'audit'
type Message = {role: 'user' | 'agent' | 'notice'; text: string; history?: Exchange[]}
type Operation = {id: string; taskId: string; unknown: boolean}
const suggestions = ['查询 2026-09 发票与银行收支', '检查 2026-09 发票用途缺口', '继续核对待补充的发票信息']
const statusLabel = {ACTIVE: '进行中', WAITING_USER: '等待补充', COMPLETED: '已结束', CANCELLED: '已取消'}
const pretty = (value: unknown) => JSON.stringify(value, null, 2)
// eslint-disable-next-line react-refresh/only-export-components -- Pure storage initializer shared with its regression tests.
export function initialOperation(read = () => sessionStorage.getItem('taxagent-pending-operation')): Operation | null {
  const unknown: Operation = {id:'unknown', taskId:'unknown', unknown:true}
  try {
    const raw = read()
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
  const [error, setError] = useState('')
  const [identityOpen, setIdentityOpen] = useState(false)
  const [cancelCandidate, setCancelCandidate] = useState<Task | null>(null)
  const [inspector, setInspector] = useState(false)
  const [messages, setMessages] = useState<Message[]>([])
  const [turn, setTurn] = useState<TurnView | null>(null)
  const [operation, setOperation] = useState<Operation | null>(initialOperation)
  const [audit, setAudit] = useState<AuditEvent[] | null>(null)
  const [goal, setGoal] = useState('')
  const [pending, setPending] = useState('')
  const [sourceTaskId, setSourceTaskId] = useState<string | null>(null)
  const [loadedHistoricalTask, setLoadedHistoricalTask] = useState(false)
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => { heading.current?.focus() }, [page])
  function rememberOperation(value: Operation | null) {
    if (value && operation) throw new Error('已有待核验操作，禁止覆盖或再次保存。')
    // Failed removal keeps both the in-memory and persisted locks intact.
    if (!value) sessionStorage.removeItem('taxagent-pending-operation')
    setOperation(value)
    // A failed setItem must stop send() before its financial request; the in-memory lock remains.
    if (value) sessionStorage.setItem('taxagent-pending-operation', pretty(value))
  }
  async function run(work: () => Promise<void>) {
    if (lock.current) return
    lock.current = true; setBusy(true); setError('')
    try { await work() } catch (e) { setError(e instanceof Error ? e.message : '请求未完成') }
    finally { lock.current = false; setBusy(false) }
  }
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
  const invoices = queriedInvoices(history, session?.enterprise ?? '')
  function reset() { setTask(null); setCancelCandidate(null); setLoadedHistoricalTask(false); setSourceTaskId(null); setMessages([]); setTurn(null); setAudit(null); setTasks([]); setInspector(false); setPage('home') }
  async function load(id: string) {
    await run(async () => {
      const context = await api.load(id)
      setTask(context.task); setLoadedHistoricalTask(true); setTurn(null); setPage('workspace')
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
      let launch: Awaited<ReturnType<typeof createAndRunFirstTurn>>
      try {
        launch = await createAndRunFirstTurn(api, requestedGoal, requestedPending, created => {
          // Publish the new task before calling the model; model failure must not hide a successful creation.
          setTask(created)
          setLoadedHistoricalTask(false)
          setTurn(null)
          setMessages([{role:'user', text:requestedGoal}])
          setSourceTaskId(null)
          setPage('workspace')
          setTasks(old => [created, ...old.filter(t => t.id !== created.id)])
        })
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
      try {
        setTasks(await api.tasks())
      } catch {
        // Do not turn a successful creation into an apparent failure if only the list refresh fails.
        setMessages(old => [...old, {role:'notice', text:'任务已创建，但任务列表暂时无法刷新；请稍后查看任务历史，勿重复创建。'}])
      }
    })
  }
  async function send(message: string, approval?: Approval) {
    if (!task) return
    await run(async () => {
      setMessages(old => [...old, {role:'user', text:message}]); setTurn(null)
      if (approval) rememberOperation({id:approval.operationId, taskId:task.id, unknown:true})
      let result: TurnView
      try { result = await api.turn(task.id, message, approval) }
      catch (e) {
        if (approval) throw new Error(`operationId=${approval.operationId} · 写入结果未确认，禁止自动重试。请核查审计与数据库。原始错误：${e instanceof Error ? e.message : '请求异常'}`)
        throw e
      }
      setTurn(result)
      setLoadedHistoricalTask(false)
      if (result.turn) {
        setTask(result.turn.task)
        setMessages(old => [...old, {role:result.turn!.result.status === 'ERROR' ? 'notice' : 'agent', text:result.turn!.result.text, history:result.turn!.result.history}])
      }
      // Absence of an update_invoice history entry does NOT prove no write occurred:
      // a tool timeout may happen after SQLite commit but before the exchange is recorded.
      // Keep the operation blocked until an actual matching DB readback verifies success.
      if (approval && verifiedWriteResult(result, approval.operationId)) rememberOperation(null)
      if (result.errorCode) setError(friendlyError(result.errorCode))
      setAudit(null)
      setTasks(await api.tasks())
    })
  }
  async function showAudit() { await run(async () => { setAudit(await api.audit()) }) }
  const navigate = (next: Page) => { setPage(next); setInspector(false) }
  const taskList = (limit?: number) => <div className="task-list">{tasks.length ? tasks.slice(0, limit).map(t => <button className="task-row" key={t.id} disabled={busy} onClick={() => void load(t.id)}><History size={17}/><span>{t.goal}<small>{t.id.slice(0,8)} · {statusLabel[t.status]}</small></span><ArrowUpRight size={17}/></button>) : <p className="empty">{selected ? '还没有任务，从一个具体问题开始。' : '选择企业与身份后，查看你的任务。'}</p>}</div>

  return <div className="app-shell">
    <a className="skip-link" href="#main">跳到主要内容</a>
    <header className="topbar"><button className="brand" onClick={() => navigate('home')} aria-label="财税 Agent 工作台"><span className="brand-mark"><Sparkles size={20}/></span><span>财税 Agent<small>HARNESS</small></span></button>
      <nav aria-label="主导航">{([['home','工作台'],['history','任务历史'],['audit','审计记录']] as const).map(([value,label]) => <button key={value} aria-current={page === value ? 'page' : undefined} onClick={() => navigate(value)}>{label}</button>)}</nav>
      <Button className="identity-button" onClick={() => setIdentityOpen(true)} disabled={busy}>{selected ? `${session.enterprise} · ${session.profile === 'operator' ? '操作员' : '只读成员'}` : '选择企业 / 身份'}<ChevronRight size={14}/></Button>
    </header>
    <div className="environment-line"><span className="dot"/> 本地演示工作空间 <span>·</span> {session?.identityNotice ?? '正在连接本地服务'}</div>
    <main id="main" tabIndex={-1} className={page === 'workspace' ? 'main workspace-main' : 'main'}>
      {error && <div role="alert" className="error-banner">{error}<Button variant="ghost" disabled={busy} onClick={() => void run(async () => { const s = await api.session(); setSession(s); if (s.profile) setTasks(await api.tasks()) })}>重新连接</Button></div>}
      {operation && <aside className="operation-warning" role="status">保存请求需核查 · operationId: <code>{operation.id}</code><br/>任务 {operation.taskId}。请检查审计和数据库；结果未核验前不会再次开放保存。编号保留在本浏览器会话中。</aside>}
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
          <form onSubmit={start} className="goal-composer"><label className="sr-only" htmlFor="goal">任务目标</label><textarea id="goal" value={goal} onChange={e => { setGoal(e.target.value); if (sourceTaskId) setSourceTaskId(null) }} placeholder="查询九月发票与银行收支，并检查发票用途…" required maxLength={4000}/><div className="composer-bottom"><span><ShieldCheck size={14}/> 写入前由你确认</span><Button variant="primary" type="submit" disabled={busy || !goal.trim() || !!openTask} aria-label={openTask ? '存在未完成任务，暂不能新建' : '创建任务'}><ArrowUp size={18}/></Button></div>
          <details className="pending"><summary>添加待补充字段（可选）</summary><label>字段，以逗号分隔<input value={pending} onChange={e => setPending(e.target.value)} placeholder="F002.purpose"/></label></details></form>
          <div className="suggestions">{suggestions.map((s,i) => <button key={s} onClick={() => { setGoal(s); setSourceTaskId(null); document.getElementById('goal')?.focus() }}><span>0{i+1}</span>{s}<ArrowUpRight size={14}/></button>)}</div>
        </section>
        <section className="recent"><div className="section-heading"><h2>最近任务</h2><Button variant="ghost" onClick={() => navigate('history')}>全部任务 <ArrowUpRight size={14}/></Button></div>{taskList(4)}</section>
      </div>}
      {page === 'history' && <section className="content-page"><div className="page-heading"><div><span className="eyebrow">YOUR TASKS</span><h1 ref={heading} tabIndex={-1}>任务历史</h1><p className="muted">恢复上下文，重新核对当前数据。</p></div><Button disabled={!selected || busy} onClick={() => void run(async () => setTasks(await api.tasks()))}>刷新任务</Button></div>{taskList()}</section>}
      {page === 'workspace' && task && <>
        <header className="workspace-heading"><div><button className="back" onClick={() => navigate('home')}>工作台 /</button><h1 ref={heading} tabIndex={-1}>{task.goal}</h1><span className="muted">{statusLabel[task.status]} · {session?.enterprise} · {task.id.slice(0,8)}</span></div><div className="workspace-actions"><Button disabled={busy || !turn || (loadedHistoricalTask && isTerminalTaskStatus(task.status))} onClick={() => navigate('review')}><FileCheck2 size={16}/>审核与结果</Button><Button aria-label="打开检查器" onClick={() => setInspector(true)}><PanelRight size={17}/></Button></div></header>
        {loadedHistoricalTask && isTerminalTaskStatus(task.status) ? <TerminalTaskPanel task={task} onCreateNew={startNewFromHistory}/> : <>
          <Thread messages={messages} busy={busy}/>
          {isTerminalTaskStatus(task.status) ? <div className="terminal-inline"><span>当前任务{statusLabel[task.status]}，已禁用后续写入。可查看本轮结果，或创建新的独立任务。</span><Button variant="primary" onClick={startNewFromHistory}>基于原任务新建查询 <ArrowUpRight size={14}/></Button></div> : <><Composer disabled={busy || !selected} onSend={m => void send(m)}/>
          <details className="supplement"><summary>补充 / 更正明确事实</summary><Supplement key={task.id + ':' + task.version} busy={busy} onSubmit={(key, value, userRequest) => void run(async () => { const updated = await api.supplement(task.id, task.version, {[key]:{value,userRequest}}); setTask(updated); setTurn(null); setMessages(old => [...old,{role:'notice',text:'明确事实已记录，发票未保存。请重新查询当前数据后审核。'}]); setTasks(await api.tasks()) })}/></details></>}
        </>}
      </>}
      {page === 'review' && task && <section className="content-page review"><div className="page-heading"><div><span className="eyebrow">HUMAN IN THE LOOP</span><h1 ref={heading} tabIndex={-1}>审核与结果</h1><p className="muted">Agent 建议不等于授权。每一项修改，由你决定。</p></div><Button onClick={() => navigate('workspace')}>返回对话</Button></div>
        <VerificationPanel turn={turn}/>
        <Review key={`${task.id}:${task.version}`} invoices={invoices} enterprise={session?.enterprise ?? ''} disabled={busy || session?.profile !== 'operator' || !!operation || task.status === 'COMPLETED' || task.status === 'CANCELLED'} onApprove={approval => void send(`现在保存以下经我逐项核对的精确修改，并查询发票核对：${pretty({enterprise:session?.enterprise,invoiceId:approval.invoiceId,changes:approval.changes})}`, approval)}/>
      </section>}
      {page === 'audit' && <section className="content-page"><span className="eyebrow">TRACEABLE BY DESIGN</span><h1 ref={heading} tabIndex={-1}>审计记录</h1><p className="muted">仅当前用户与企业的真实元数据；不展示财务原文。</p><details onToggle={e => { if (e.currentTarget.open && audit === null && selected && !busy) void showAudit() }}><summary>展开真实审计记录</summary><Button disabled={!selected || busy} onClick={() => void showAudit()}>刷新审计</Button><Audit events={audit}/></details></section>}
    </main>
    <footer>受约束执行 <span>·</span> 可回读核验 <span>·</span> 模型文字不是财务执行证据</footer>
    <Modal open={cancelCandidate !== null} onOpenChange={open => { if (!open && !busy) setCancelCandidate(null) }} title="确认结束这项任务？" description="只有你明确确认后才会结束旧任务；不会自动创建新任务或执行财务写入。">
      {cancelCandidate && <div className="cancel-task-detail"><p><strong>{cancelCandidate.goal}</strong></p><small>{cancelCandidate.id.slice(0,8)} · {statusLabel[cancelCandidate.status]}</small><p>结束后任务不能继续执行，但历史记录和审计不会被清除。已发生的财务写入不会撤销。</p>
        {operation && <p role="alert">仍有待核验的财务操作，禁止结束任务。请先核查 operationId。</p>}
        <div className="open-task-actions"><Button disabled={busy} onClick={() => setCancelCandidate(null)}>返回</Button><Button variant="primary" disabled={busy || !!operation} onClick={() => void cancelOpenTask()}>确认结束旧任务</Button></div>
      </div>}
    </Modal>
    <Modal open={identityOpen} onOpenChange={setIdentityOpen} title="选择工作空间" description="本地模拟身份，不是生产认证。权限由后端逐次校验。">
      {error && <p role="alert" className="error-banner">{error}</p>}
      <Identity busy={busy} session={session} onSelect={(profile, enterprise) => void run(async () => { const s = await api.select(profile, enterprise); reset(); setSession(s); setTasks(await api.tasks()); setIdentityOpen(false) })}/>
      {selected && <Button disabled={busy} onClick={() => void run(async () => { const s = await api.logout(); reset(); setSession(s); setIdentityOpen(false) })}>退出本地会话</Button>}
    </Modal>
    <Modal open={inspector} onOpenChange={setInspector} title="任务检查器" description="计划、实际执行与审计，按需展开。" drawer>
      {task && <><h3>任务计划 / 待办</h3><p>{task.goal}</p>{task.pending.length ? <ul>{task.pending.map(p => <li key={p}>{p}</li>)}</ul> : <p className="muted">没有结构化待办。当前 API 未提供实时计划事件。</p>}<p className="muted">状态：{statusLabel[task.status]} · 版本 {task.version}</p><details><summary>历史工具摘要（不是当前执行证据）</summary><pre>{pretty(task.recentTools)}</pre></details><ToolGroup history={history}/><details><summary>任务原始数据（可能包含敏感字段）</summary><pre>{pretty(task)}</pre></details></>}
      <details onToggle={e => { if (e.currentTarget.open && audit === null && selected && !busy) void showAudit() }}><summary>真实审计</summary><Button disabled={!selected || busy} onClick={() => void showAudit()}>刷新审计</Button><Audit events={audit}/></details>
    </Modal>
  </div>
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
function Thread({messages,busy}: {messages:Message[]; busy:boolean}) {
  const bottom = useRef<HTMLDivElement>(null)
  useEffect(() => { bottom.current?.scrollIntoView({block:'nearest'}) }, [messages, busy])
  return <section className="thread" aria-label="任务对话"><div className="thread-inner" role="log" aria-live="polite">{messages.map((m,i) => <article key={i} className={`message ${m.role}`}><div className="message-label">{m.role === 'agent' ? <><Sparkles size={15}/>财税 Agent <span>模型生成 · 不代表保存成功</span></> : m.role === 'user' ? '你' : <><ShieldCheck size={14}/>工作空间</>}</div><p>{m.text}</p>{m.history && <ToolGroup history={m.history}/>}</article>)}{busy && <p className="request-state" role="status"><CircleDashed size={16}/>请求处理中，等待后端完整响应…</p>}<div ref={bottom}/></div></section>
}
function Composer({disabled,onSend}: {disabled:boolean; onSend:(m:string)=>void}) {
  const [message,setMessage] = useState('')
  function submit(e: FormEvent) { e.preventDefault(); if (!disabled && message.trim()) { onSend(message.trim()); setMessage('') } }
  return <form className="composer" onSubmit={submit}><label className="sr-only" htmlFor="message">向 Agent 发送消息</label><textarea id="message" value={message} onChange={e => setMessage(e.target.value)} disabled={disabled} placeholder="补充问题，或让 Agent 查询当前数据库…" maxLength={8000} onKeyDown={e => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) submit(e) }}/><div className="composer-bottom"><span><ShieldCheck size={13}/> 发送消息不会授予保存权限 <span className="keyboard-hint">· ⌘ / Ctrl + Enter</span></span><Button type="submit" variant="primary" disabled={disabled || !message.trim()} aria-label="发送消息"><ArrowUp size={18}/></Button></div></form>
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
