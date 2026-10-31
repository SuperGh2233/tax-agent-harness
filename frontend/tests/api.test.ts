import { afterEach, expect, test, vi } from 'vitest'
import { ApiError, createAndRunFirstTurn, DemoApi, findOpenTask, isTerminalTaskStatus, queriedInvoices, verifiedWriteResult } from '../src/api'
import type { Exchange, Task, TurnView } from '../src/api'
import { initialOperation } from '../src/App'
// @ts-expect-error Node built-ins are available in Vitest; this browser project has no @types/node.
import { readFileSync } from 'node:fs'
// @ts-expect-error Test-only VM avoids adding a dependency or executing browser requests.
import { createContext, runInContext } from 'node:vm'

// Execute the actual default-page script with isolated DOM/storage/fetch, never a backend.
async function staticPage(storage = new Map<string, string>(), failure?: 'read' | 'write' | 'remove') {
  const element = () => ({textContent:'', disabled:false, value:'', className:'', onclick:() => Promise.resolve(),
    setAttribute:vi.fn(), after:vi.fn(), append:vi.fn(), replaceChildren:vi.fn(), addEventListener:vi.fn()})
  const nodes = new Map<string, ReturnType<typeof element>>()
  const get = (id:string) => {
    if (!nodes.has(id)) nodes.set(id, element())
    return nodes.get(id)!
  }
  const warnings: ReturnType<typeof element>[] = []
  const fetcher = vi.fn(async (url:string) => Response.json(url.endsWith('/session')
    ? {csrfToken:'test',profile:'',enterprise:''} : []))
  const storageApi = {
    getItem:vi.fn((key:string) => {if (failure === 'read') throw new Error('storage unavailable'); return storage.get(key) ?? null}),
    setItem:vi.fn((key:string,value:string) => {if (failure === 'write') throw new Error('storage unavailable'); storage.set(key,value)}),
    removeItem:vi.fn((key:string) => {if (failure === 'remove') throw new Error('storage unavailable'); storage.delete(key)}),
  }
  const context = createContext({document:{getElementById:get, querySelectorAll:() => [...nodes.values()],
    createElement:() => {const node = element(); warnings.push(node); return node}},
    sessionStorage:storageApi,
    fetch:fetcher, window:{confirm:() => true}, crypto:{randomUUID:() => 'op-static'}})
  runInContext(readFileSync(new URL('../../src/main/resources/static/demo.js', import.meta.url),'utf8'),context)
  await vi.waitFor(() => expect(runInContext('busy',context)).toBe(false))
  runInContext("active={id:'task-static'};selected={enterprise:'C001'}",context)
  get('invoice').value='F002'; get('field').value='purpose'; get('value').value='private-value'
  return {get, warnings, storage, storageApi, fetcher}
}

const invalidStoredOperations = ['', '{', 'null', '[]', '{}',
  '{"id":"op","taskId":"task","unknown":false}', '{"id":"","taskId":"task","unknown":true}']
test.each([...invalidStoredOperations, 'getItem throws'])
('static page fails closed for damaged operation storage: %s', async raw => {
  const storage = new Map([['taxagent-pending-operation',raw]])
  const page = await staticPage(storage, raw === 'getItem throws' ? 'read' : undefined)
  expect(page.get('approve').disabled).toBe(true)
  expect(page.warnings[0].textContent).toContain('operationId=unknown')
  expect(page.warnings[0].textContent).toContain('taskId=unknown')
  await page.get('approve').onclick()
  expect(page.fetcher.mock.calls.map(call => call[0])).toEqual(['/api/demo/session'])
  await page.get('refresh').onclick()
  expect(page.fetcher.mock.calls.map(call => call[0])).toEqual(['/api/demo/session','/api/demo/tasks','/api/demo/audit'])
  expect(page.get('approve').disabled).toBe(true)
  expect(storage.get('taxagent-pending-operation')).toBe(raw)
  expect(page.storageApi.setItem).not.toHaveBeenCalled()
  expect(page.storageApi.removeItem).not.toHaveBeenCalled()
})

test.each(invalidStoredOperations)('React initializer fails closed for invalid stored operation: %s', raw => {
  expect(initialOperation(() => raw)).toEqual({id:'unknown',taskId:'unknown',unknown:true})
})
test('React initializer distinguishes absent, unreadable and valid pending operation storage', () => {
  expect(initialOperation(() => null)).toBeNull()
  expect(initialOperation(() => {throw new Error('storage unavailable')})).toEqual({id:'unknown',taskId:'unknown',unknown:true})
  expect(initialOperation(() => '{"id":"pending","taskId":"task","unknown":true}')).toEqual({id:'pending',taskId:'task',unknown:true})
})

test('static page keeps its in-memory lock and never sends approval when setItem fails', async () => {
  const page = await staticPage(new Map(), 'write')
  await page.get('approve').onclick()
  expect(page.get('approve').disabled).toBe(true)
  expect(page.warnings[0].textContent).toContain('op-static')
  await page.get('approve').onclick()
  expect(page.fetcher.mock.calls.map(call => call[0])).toEqual(['/api/demo/session'])
})

test('static page keeps both locks when removeItem fails after matching verified readback', async () => {
  const page = await staticPage(new Map(), 'remove')
  page.fetcher.mockImplementationOnce(async () => Response.json({operationId:'op-static',verification:{verified:true},turn:null}))
  await page.get('approve').onclick()
  expect(page.storageApi.removeItem).toHaveBeenCalledWith('taxagent-pending-operation')
  expect(JSON.parse(page.storage.get('taxagent-pending-operation')!)).toEqual({id:'op-static',taskId:'task-static',unknown:true})
  expect(page.get('approve').disabled).toBe(true)
  expect(page.warnings[0].textContent).toContain('op-static')
  const requests = page.fetcher.mock.calls.length
  await page.get('approve').onclick()
  expect(page.fetcher).toHaveBeenCalledTimes(requests)
})

test.each(['network','invalid JSON','HTTP error','unverified','wrong operation','nonboolean verification'])
('static page retains the operation lock after %s, across reload, while queries remain usable', async mode => {
  const page = await staticPage()
  page.fetcher.mockImplementationOnce(async () => {
    if (mode === 'network') throw new TypeError('connection lost')
    if (mode === 'invalid JSON') return new Response('invalid')
    if (mode === 'HTTP error') return Response.json({errorCode:'STORAGE_ERROR'},{status:500})
    return Response.json({operationId:mode === 'wrong operation' ? 'wrong' : 'op-static',
      verification:{verified:mode === 'nonboolean verification' ? 'true' : mode !== 'unverified'},turn:null})
  })
  await page.get('approve').onclick()
  expect(JSON.parse(page.storage.get('taxagent-pending-operation')!)).toEqual({id:'op-static',taskId:'task-static',unknown:true})
  expect(page.get('approve').disabled).toBe(true)
  const requests = page.fetcher.mock.calls.length
  await page.get('approve').onclick()
  expect(page.fetcher).toHaveBeenCalledTimes(requests)
  const reloaded = await staticPage(page.storage)
  expect(reloaded.get('approve').disabled).toBe(true)
  expect(reloaded.warnings[0].textContent).toContain('op-static')
  expect(reloaded.warnings[0].textContent).toContain('task-static')
  await reloaded.get('refresh').onclick()
  expect(reloaded.fetcher.mock.calls.map(call => call[0])).toEqual(['/api/demo/session','/api/demo/tasks','/api/demo/audit'])
  expect(reloaded.get('approve').disabled).toBe(true)
})

test('static page clears only a matching verified response and persists no financial content', async () => {
  const page = await staticPage()
  page.fetcher.mockImplementationOnce(async () => {
    expect(JSON.parse(page.storage.get('taxagent-pending-operation')!)).toEqual({id:'op-static',taskId:'task-static',unknown:true})
    return Response.json({operationId:'op-static',verification:{verified:true},turn:null})
  })
  await page.get('approve').onclick()
  expect(page.storage.has('taxagent-pending-operation')).toBe(false)
  expect(page.get('approve').disabled).toBe(false)
  expect(page.warnings[0].textContent).toBe('')
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
