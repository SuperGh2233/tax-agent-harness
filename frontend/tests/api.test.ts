import { afterEach, expect, test, vi } from 'vitest'
import { ApiError, canVerifyOperation, citedRules, createAndRunFirstTurn, DemoApi, deriveExecutionPlan, findOpenTask, friendlyError, isTerminalTaskStatus, provenNotExecuted, queriedInvoices, verifiedWriteResult, verifyPendingOperation } from '../src/api'
import type { Exchange, Task, TurnView } from '../src/api'
import { initialOperation } from '../src/App'


const invalidStoredOperations = ['', '{', 'null', '[]', '{}',
  '{"id":"op","taskId":"task","unknown":false}', '{"id":"","taskId":"task","unknown":true}']

test.each(invalidStoredOperations)('React initializer fails closed for invalid stored operation: %s', raw => {
  expect(initialOperation(() => raw)).toEqual({id:'unknown',taskId:'unknown',unknown:true})
})
test('React initializer distinguishes absent, unreadable and valid pending operation storage', () => {
  expect(initialOperation(() => null)).toBeNull()
  expect(initialOperation(() => {throw new Error('storage unavailable')})).toEqual({id:'unknown',taskId:'unknown',unknown:true})
  expect(initialOperation(() => '{"id":"pending","taskId":"task","unknown":true}')).toEqual({id:'pending',taskId:'task',unknown:true})
})







const pendingVerification = {id:'11111111-1111-4111-8111-111111111111',taskId:'22222222-2222-4222-8222-222222222222',unknown:true}
const verificationModes = ['NOT_FOUND','DIVERGED','network','HTTP error','invalid JSON','wrong operation','nonboolean verified','false verified','missing status','null','remove failure','VERIFIED']
function verificationResponse(mode: string) {
  if (mode === 'network') throw new TypeError('lost response')
  if (mode === 'HTTP error') return Response.json({errorCode:'AUDIT_STORAGE_ERROR'}, {status:500})
  if (mode === 'invalid JSON') return new Response('{')
  if (mode === 'null') return Response.json(null)
  return Response.json({operationId:mode === 'wrong operation' ? 'other' : pendingVerification.id,
    status:mode === 'missing status' ? undefined : ['NOT_FOUND','DIVERGED'].includes(mode) ? mode : 'VERIFIED',
    verification:{verified:mode === 'nonboolean verified' ? 'true' : mode !== 'false verified'}})
}
test.each(verificationModes)('React manual verification helper retains lock unless exact verified and storage removal succeeds: %s', async mode => {
  const fetcher = vi.fn(async (url:string) => { void url; return verificationResponse(mode) })
  vi.stubGlobal('fetch',fetcher)
  let locked = true
  const clear = vi.fn(() => { if (mode === 'remove failure') throw new Error('storage failure'); locked = false })
  const action = verifyPendingOperation(new DemoApi(), pendingVerification, clear)
  if (mode === 'VERIFIED') await action
  else await expect(action).rejects.toThrow()
  expect(locked).toBe(mode !== 'VERIFIED')
  expect(fetcher).toHaveBeenCalledTimes(1)
  expect(fetcher.mock.calls[0][0]).toBe(`/api/demo/tasks/${pendingVerification.taskId}/operations/${pendingVerification.id}/verify`)
})
test('React helper refuses placeholder IDs without a request', async () => {
  const api = {verify:vi.fn()}
  const clear = vi.fn()
  expect(canVerifyOperation(null)).toBe(false)
  await expect(verifyPendingOperation(api,{id:'unknown',taskId:'unknown'},clear)).rejects.toThrow()
  expect(api.verify).not.toHaveBeenCalled(); expect(clear).not.toHaveBeenCalled()
})
test('verify API sends a single CSRF POST without approval or payload', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(session)).mockResolvedValueOnce(verificationResponse('VERIFIED'))
  vi.stubGlobal('fetch',fetcher)
  const api = new DemoApi(); await api.session(); await api.verify('task/id','operation/id')
  expect(fetcher.mock.calls[1][0]).toBe('/api/demo/tasks/task%2Fid/operations/operation%2Fid/verify')
  expect(fetcher.mock.calls[1][1]).toMatchObject({method:'POST',credentials:'same-origin',cache:'no-store',headers:{'X-CSRF-Token':'first'},body:undefined})
})

test('approved write remains blocked without matching verified readback, even if tool history is empty', () => {
  const noHistory: TurnView = {
    turn: {task: {id: 'task', status: 'ACTIVE', version: 1, goal: 'test', pending: [], supplied: {}, question: null, recentTools: []}, result: {status: 'ERROR', text: 'TOOL_TIMEOUT', errorCode: 'TOOL_TIMEOUT', history: []}},
    verification: null, errorCode: 'TOOL_TIMEOUT', message: 'Unknown write result', operationId: 'op-1',
  }
  expect(verifiedWriteResult(noHistory, 'op-1')).toBe(false)
  expect(verifiedWriteResult({...noHistory, turn: null}, 'op-1')).toBe(false)
  expect(verifiedWriteResult({...noHistory, verification: {key:{enterpriseId:'C001',invoiceId:'F002'},purpose:'a',remarks:'',verified:false}}, 'op-1')).toBe(false)
  const verified = {...noHistory, verification: {key:{enterpriseId:'C001',invoiceId:'F002'},purpose:'a',remarks:'',verified:true}}
  expect(verifiedWriteResult(verified, 'different-op')).toBe(false)
  expect(verifiedWriteResult(verified, 'op-1')).toBe(true)
})

test('proven not-executed terminal receipt is the only non-verified result that releases the approval', () => {
  const notExecuted: TurnView = {
    turn: {task: {id: 'task', status: 'ACTIVE', version: 1, goal: 'test', pending: [], supplied: {}, question: null, recentTools: []}, result: {status: 'ERROR', text: '本次请求未成功完成：WRITE_NOT_EXECUTED', errorCode: 'WRITE_NOT_EXECUTED', history: []}},
    verification: null, errorCode: 'WRITE_NOT_EXECUTED', message: '本轮未执行保存；旧批准已结束，如需保存请重新确认', operationId: 'op-1',
  }
  expect(provenNotExecuted(notExecuted, 'op-1')).toBe(true)
  expect(provenNotExecuted(notExecuted, 'other')).toBe(false)
  expect(provenNotExecuted({...notExecuted, verification: {key:{enterpriseId:'C001',invoiceId:'F002'},purpose:'a',remarks:'',verified:false}}, 'op-1')).toBe(false)
  expect(provenNotExecuted({...notExecuted, errorCode: 'TOOL_TIMEOUT'}, 'op-1')).toBe(false)
  expect(provenNotExecuted({...notExecuted, errorCode: 'WRITE_NOT_VERIFIED'}, 'op-1')).toBe(false)
  expect(friendlyError('WRITE_NOT_EXECUTED')).toContain('本轮未执行保存')
  expect(friendlyError('WRITE_NOT_VERIFIED')).toContain('写入结果未核验')
})

test('only completed and cancelled tasks are terminal history; active and waiting tasks remain resumable', () => {
  expect(isTerminalTaskStatus('COMPLETED')).toBe(true)
  expect(isTerminalTaskStatus('CANCELLED')).toBe(true)
  expect(isTerminalTaskStatus('ACTIVE')).toBe(false)
  expect(isTerminalTaskStatus('WAITING_USER')).toBe(false)
})

test('open-task limit detects ACTIVE and WAITING_USER without hiding completed history', () => {
  const base: Task = {id:'first',status:'COMPLETED',version:1,goal:'历史记录',pending:[],supplied:{},question:null,recentTools:[]}
  expect(findOpenTask([base])).toBeUndefined()
  const active: Task = {...base,id:'current',status:'ACTIVE'}
  const waiting: Task = {...base,id:'pending',status:'WAITING_USER'}
  expect(findOpenTask([base,waiting])).toEqual(waiting)
  expect(findOpenTask([base,active,waiting])).toEqual(active)
})

test('cancel task sends current version and explicit confirmation with CSRF', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(session))
    .mockResolvedValueOnce(Response.json({id:'id',status:'CANCELLED',version:2}))
  vi.stubGlobal('fetch',fetcher)
  const api = new DemoApi()
  await api.session()
  await api.cancel('task/id',1)
  expect(fetcher.mock.calls[1][0]).toBe('/api/demo/tasks/task%2Fid/cancel')
  expect(fetcher.mock.calls[1][1]).toMatchObject({
    method:'POST',credentials:'same-origin',cache:'no-store',
    headers:{'X-CSRF-Token':'first'},
    body:JSON.stringify({version:1,confirmed:true}),
  })
  expect(fetcher).toHaveBeenCalledTimes(2)
})

test('database open-task conflict is surfaced without retrying task creation', async () => {
  const fetcher = vi.fn().mockResolvedValue(Response.json({errorCode:'CONFLICT'},{status:409}))
  vi.stubGlobal('fetch',fetcher)
  await expect(new DemoApi().start('新任务',[])).rejects.toMatchObject({code:'CONFLICT',status:409})
  expect(fetcher).toHaveBeenCalledTimes(1)
})

test('new task runs the first user request exactly once using its new task ID, without approval', async () => {
  const created: Task = {id:'new-task',status:'ACTIVE',version:0,goal:'检查九月发票',pending:[],supplied:{},question:null,recentTools:[]}
  const response: TurnView = {turn:{task:created,result:{status:'FINISHED',text:'查询完成',errorCode:null,history:[]}},verification:null,errorCode:null,message:'',operationId:null}
  const api = {start:vi.fn().mockResolvedValue(created), turn:vi.fn().mockResolvedValue(response)}
  const published: string[] = []
  const result = await createAndRunFirstTurn(api,created.goal,[],t => published.push(t.id))
  expect(api.start).toHaveBeenCalledTimes(1)
  expect(api.start).toHaveBeenCalledWith(created.goal,[])
  expect(api.turn).toHaveBeenCalledTimes(1)
  expect(api.turn).toHaveBeenCalledWith('new-task',created.goal)
  expect(published).toEqual(['new-task'])
  expect(result).toEqual({created,response,executionError:null})
})

test('a failed first turn preserves a successfully created task and does not retry', async () => {
  const created: Task = {id:'persisted-task',status:'ACTIVE',version:0,goal:'核对发票',pending:[],supplied:{},question:null,recentTools:[]}
  const api = {start:vi.fn().mockResolvedValue(created),turn:vi.fn().mockRejectedValue(new Error('MODEL_NOT_CONFIGURED'))}
  const published: Task[] = []
  const result = await createAndRunFirstTurn(api,created.goal,[],t => published.push(t))
  expect(published).toEqual([created])
  expect(api.turn).toHaveBeenCalledTimes(1)
  expect(result.created).toEqual(created)
  expect(result.response).toBeNull()
  expect(result.executionError?.message).toBe('MODEL_NOT_CONFIGURED')
})

test('a failed task creation must never trigger a model turn', async () => {
  const api = {start:vi.fn().mockRejectedValue(new Error('creation failed')),turn:vi.fn()}
  const onCreated = vi.fn()
  await expect(createAndRunFirstTurn(api,'检查发票',[],onCreated)).rejects.toThrow('creation failed')
  expect(api.turn).not.toHaveBeenCalled()
  expect(onCreated).not.toHaveBeenCalled()
})

afterEach(() => vi.unstubAllGlobals())
const session = {csrfToken:'first',profile:'',enterprise:'',identityNotice:'local'}
test('REST uses session token rotation, same-origin and no-store; approval stays out of ordinary turns', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(session))
    .mockResolvedValueOnce(Response.json({...session,csrfToken:'rotated',profile:'operator',enterprise:'C001'}))
    .mockResolvedValue(Response.json({}))
  vi.stubGlobal('fetch', fetcher)
  const api = new DemoApi()
  await api.session(); await api.select('operator','C001'); await api.turn('a/b','query')
  expect(fetcher.mock.calls[1][1]).toMatchObject({credentials:'same-origin',cache:'no-store',headers:{'X-CSRF-Token':'first'},body:JSON.stringify({profile:'operator',enterprise:'C001'})})
  expect(fetcher.mock.calls[2][0]).toBe('/api/demo/tasks/a%2Fb/turn')
  expect(fetcher.mock.calls[2][1].headers['X-CSRF-Token']).toBe('rotated')
  expect(JSON.parse(fetcher.mock.calls[2][1].body)).toEqual({message:'query'})
})
test('unknown write result is never automatically retried', async () => {
  const fetcher = vi.fn().mockRejectedValue(new TypeError('lost connection'))
  vi.stubGlobal('fetch',fetcher)
  await expect(new DemoApi().turn('task','save',{operationId:'operation',invoiceId:'F002',changes:{purpose:'office'},confirmed:true})).rejects.toEqual(new ApiError('NETWORK_UNKNOWN'))
  expect(fetcher).toHaveBeenCalledTimes(1)
})
test('latest actual invoice query is the only review source; model prose and write results are not evidence', () => {
  const invoice = {key:{enterprise_id:'C001',invoice_id:'F002'},invoice_date:'2026-09-03',seller:'fixture',item:'fixture',amount_cents:10000,purpose:'',remarks:''}
  const exchange = (name:string,data:unknown,error:unknown=null):Exchange => ({call:{id:'1',name,arguments:'{}'},resultJson:JSON.stringify({data,error})})
  expect(queriedInvoices([exchange('query_invoices',{rows:[invoice]})],'C001')).toEqual([invoice])
  expect(queriedInvoices([exchange('query_invoices',{rows:[invoice]})],'C002')).toEqual([])
  expect(queriedInvoices([exchange('update_invoice',{...invoice,verified:true})],'C001')).toEqual([])
  expect(queriedInvoices([exchange('query_invoices',{rows:[invoice]}),exchange('update_invoice',{verified:true})],'C001')).toEqual([])
  expect(queriedInvoices([exchange('query_invoices',{rows:[invoice]}),exchange('query_invoices',null,{code:'STORAGE_ERROR'})],'C001')).toEqual([])
  expect(queriedInvoices([exchange('query_invoices',{rows:[{...invoice,amount_cents:1.2}]})],'C001')).toEqual([])
})

test('execution plan is derived from task state and tool history, not model prose', () => {
  const exchange:Exchange = {call:{id:'1',name:'query_invoices',arguments:'{}'},resultJson:JSON.stringify({data:{rows:[]},error:null})}
  const waiting:Task = {id:'t',status:'WAITING_USER',version:2,goal:'检查用途',pending:['F002.purpose'],supplied:{},question:null,recentTools:[]}
  const plan = deriveExecutionPlan(waiting,[exchange],null)
  expect(plan.find(step => step.key === 'facts')?.state).toBe('done')
  expect(plan.find(step => step.key === 'input')).toMatchObject({state:'waiting'})
  expect(plan.find(step => step.key === 'close')).toMatchObject({state:'waiting'})
  expect(plan.find(step => step.key === 'write')?.detail).toContain('尚未授权')
  const completed:Task = {...waiting,status:'COMPLETED',pending:[]}
  expect(deriveExecutionPlan(completed,[exchange],null).find(step => step.key === 'close')?.state).toBe('done')
})

test('citedRules only uses query_rules tool output and deduplicates by id/version', () => {
  const history:Exchange[] = [
    {call:{id:'1',name:'query_rules',arguments:'{}'},resultJson:JSON.stringify({data:{rules:[
      {key:{rule_id:'R004',version:'1.0'},name:'银行资料说明'}]},error:null})},
    {call:{id:'2',name:'query_invoices',arguments:'{}'},resultJson:JSON.stringify({data:{rules:[
      {key:{rule_id:'R999',version:'9.9'}}]},error:null})},
    {call:{id:'3',name:'query_rules',arguments:'{}'},resultJson:'not-json'}
  ]
  expect(citedRules(history)).toEqual([{id:'R004',version:'1.0',name:'银行资料说明'}])
  expect(citedRules([])).toEqual([])
})

test('friendly errors stay natural without claiming an unverified success', () => {
  expect(friendlyError('MODEL_FAILURE')).toContain('没有给出可执行的结论')
  expect(friendlyError('TOOL_TIMEOUT')).toContain('不要重复写入')
  expect(friendlyError('WRITE_NOT_EXECUTED')).toContain('本轮未执行保存')
  expect(friendlyError('WRITE_NOT_VERIFIED')).toContain('写入结果未核验')
})
