import { afterEach, expect, test, vi } from 'vitest'
import { agentProblem, ApiError, contextSummary, memoryProblem, impactMessage, skillProblem, canVerifyOperation, usableAgents, citedKnowledge, citedRules, createAndRunFirstTurn, DemoApi, deriveExecutionPlan, describeUpload, findOpenTask, friendlyError, isTerminalTaskStatus, issueLocation, issueReason, MAX_UPLOAD_BYTES, provenNotExecuted, queriedInvoices, uploadProblem, verifiedWriteResult, verifyPendingOperation } from '../src/api'
import type { AgentDef, AgentSpec, Exchange, Task, TurnView, UploadResult } from '../src/api'
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

test('a stated but unsaved invoice value keeps the save step open (T35)', () => {
  const exchange:Exchange = {call:{id:'1',name:'query_invoices',arguments:'{}'},resultJson:JSON.stringify({data:{rows:[]},error:null})}
  const stated:Task = {id:'t',status:'WAITING_USER',version:3,goal:'检查用途',pending:[],supplied:{'F002.purpose':{value:'办公室采购',userRequest:'用户原话'}},question:null,recentTools:[],awaitingSave:['F002.purpose']}
  const plan = deriveExecutionPlan(stated,[exchange],null)
  expect(plan.find(step => step.key === 'input')?.state).toBe('done')
  expect(plan.find(step => step.key === 'write')).toMatchObject({state:'waiting'})
  expect(plan.find(step => step.key === 'write')?.detail).toContain('待保存：F002.purpose')
  expect(plan.find(step => step.key === 'close')?.detail).toContain('等待确认保存或取消')
  // Tasks stored before T35 have no awaitingSave field and keep the old plan.
  const legacy:Task = {...stated,awaitingSave:undefined}
  expect(deriveExecutionPlan(legacy,[exchange],null).find(step => step.key === 'write')?.state).toBe('optional')
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

const storedFile = {id:'f1',enterpriseId:'C001',name:'十月.xlsx',version:2,sha256:'ab',kind:'XLSX' as const,sizeBytes:10,uploadedBy:'U001',uploadedAt:'2026-11-18T10:00:00Z',knowledgeEnabled:true,latest:true}
const imported: UploadResult = {status:'IMPORTED',file:storedFile,invoicesImported:1,bankEntriesImported:0,
  conflicts:[{sheet:'发票',row:5,recordId:'F002',reason:'EXISTING_RECORD'}],skipped:[],errors:[],chunks:3}

test('upload pre-check only spares doomed uploads; the server still decides', () => {
  expect(uploadProblem({name:'数据.XLSX',size:1})).toBeNull()
  expect(uploadProblem({name:'规则.pdf',size:MAX_UPLOAD_BYTES})).toBeNull()
  expect(uploadProblem({name:'说明.txt',size:1})).toContain('只支持')
  expect(uploadProblem({name:'空.docx',size:0})).toContain('空')
  expect(uploadProblem({name:'大.pdf',size:MAX_UPLOAD_BYTES + 1})).toContain('10 MB')
})
test('upload summary states exactly what was stored and never claims more', () => {
  expect(describeUpload(imported)).toBe('已保存为 十月.xlsx 第 2 版；新增发票 1 张、流水 0 条；1 条与已有记录同编号，未覆盖；共 3 个可引用文本段。')
  expect(describeUpload({...imported,status:'DUPLICATE',invoicesImported:0,conflicts:[]})).toContain('本次没有新增任何数据')
  const rejected: UploadResult = {...imported,status:'REJECTED',file:null,invoicesImported:0,conflicts:[],chunks:0,
    errors:[{sheet:'发票',row:4,recordId:'F101',reason:'INVALID_AMOUNT'},{sheet:null,row:0,recordId:null,reason:'NOT_TEXT_PDF'}]}
  expect(describeUpload(rejected)).toBe('文件未导入：发现 2 处问题，系统没有保存任何内容。')
  expect(issueLocation(rejected.errors[0])).toBe('发票 · 第4行 · F101')
  expect(issueLocation(rejected.errors[1])).toBe('整个文件')
  expect(issueReason('MISSING_COLUMNS:[AMOUNT]')).toBe('表头缺少必需列 [AMOUNT]')
  expect(issueReason('SOMETHING_NEW')).toBe('SOMETHING_NEW')
})
test('upload API sends multipart with CSRF and lets the browser set the content type', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(session)).mockResolvedValueOnce(Response.json(imported))
  vi.stubGlobal('fetch',fetcher)
  const api = new DemoApi(); await api.session()
  const result = await api.upload(new File(['x'],'十月.xlsx'))
  expect(result.status).toBe('IMPORTED')
  const [url, init] = fetcher.mock.calls[1]
  expect(url).toBe('/api/demo/files')
  expect(init).toMatchObject({method:'POST',credentials:'same-origin',cache:'no-store',headers:{'X-CSRF-Token':'first'}})
  expect(init.headers).not.toHaveProperty('Content-Type')
  expect(init.body).toBeInstanceOf(FormData)
  expect((init.body as FormData).get('file')).toBeInstanceOf(File)
})
test('upload API maps server refusals and lost responses to explicit errors', async () => {
  vi.stubGlobal('fetch',vi.fn().mockResolvedValueOnce(Response.json({errorCode:'PERMISSION_DENIED'},{status:403})))
  await expect(new DemoApi().upload(new File(['x'],'a.xlsx'))).rejects.toMatchObject({code:'PERMISSION_DENIED',status:403})
  vi.stubGlobal('fetch',vi.fn().mockRejectedValueOnce(new TypeError('lost')))
  await expect(new DemoApi().upload(new File(['x'],'a.xlsx'))).rejects.toMatchObject({code:'NETWORK_UNKNOWN'})
  expect(friendlyError('REQUEST_TOO_LARGE')).toContain('10 MB')
})
test('knowledge citations come only from successful search_knowledge results', () => {
  const ok: Exchange = {call:{id:'k',name:'search_knowledge',arguments:'{}'}, resultJson: JSON.stringify({data:{hits:[
    {source_kind:'RULE',source_id:'R004',source_name:'银行资料说明',version:'1.0',location:'第5行',text:'…',score:2},
    {source_kind:'FILE',source_id:'f1',source_name:'差旅报销制度.docx',version:'v2',location:'第2段',text:'…',score:1},
    {source_kind:'FILE',source_name:42,version:'v1',location:'x'}]},error:null})}
  const failed: Exchange = {call:{id:'e',name:'search_knowledge',arguments:'{}'}, resultJson: JSON.stringify({data:null,error:{code:'PERMISSION_DENIED'}})}
  const other: Exchange = {call:{id:'q',name:'query_rules',arguments:'{}'}, resultJson: ok.resultJson}
  expect(citedKnowledge([ok, failed, other])).toEqual([
    {kind:'RULE',name:'R004',version:'1.0',location:'第5行'}, {kind:'FILE',name:'差旅报销制度.docx',version:'v2',location:'第2段'}])
})
test('knowledge switch posts an explicit flag with CSRF', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(session)).mockResolvedValueOnce(Response.json({...storedFile,knowledgeEnabled:false}))
  vi.stubGlobal('fetch',fetcher)
  const api = new DemoApi(); await api.session()
  expect((await api.setKnowledge('f/1', false)).knowledgeEnabled).toBe(false)
  expect(fetcher.mock.calls[1][0]).toBe('/api/demo/files/f%2F1/knowledge')
  expect(fetcher.mock.calls[1][1]).toMatchObject({method:'POST',headers:{'X-CSRF-Token':'first'},body:JSON.stringify({enabled:false})})
})

const readerSpec: AgentSpec = {name:'只读查询员',responsibility:'只回答查询',instructions:'',modelProfile:'default',model:null,tools:['query_rules'],enterprises:['C001']}
const agentDef = (id: string, enabled: boolean, enterprises: string[]): AgentDef => ({id,version:1,spec:{...readerSpec,enterprises},enabled,changedBy:'U001',changedAt:'2026-11-18T00:00:00Z'})
test('only enabled agents covering the enterprise are offered for a new task', () => {
  const agents = [agentDef('finance-assistant',true,['C001','C002']), agentDef('reader',true,['C001']), agentDef('off',false,['C001']), agentDef('c2',true,['C002'])]
  expect(usableAgents(agents,'C001').map(a => a.id)).toEqual(['finance-assistant','reader'])
  expect(usableAgents(agents,'C002').map(a => a.id)).toEqual(['finance-assistant','c2'])
})
test('agent form mirrors the server rules before sending', () => {
  expect(agentProblem('finance-reader', readerSpec)).toBeNull()
  expect(agentProblem('Bad Id', readerSpec)).toContain('编号')
  expect(agentProblem('reader', {...readerSpec, name:' '})).toContain('名称')
  expect(agentProblem('reader', {...readerSpec, tools:[]})).toContain('能力')
  expect(agentProblem('reader', {...readerSpec, tools:['update_invoice']})).toContain('回读')
  expect(agentProblem('reader', {...readerSpec, enterprises:[]})).toContain('企业')
  expect(agentProblem('reader', {...readerSpec, instructions:'x'.repeat(4001)})).toContain('4000')
})
test('agent calls send exact bodies with CSRF, and a chosen agent reaches task creation', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(session))
    .mockResolvedValueOnce(Response.json(agentDef('reader',true,['C001'])))
    .mockResolvedValueOnce(Response.json(agentDef('reader',false,['C001'])))
  vi.stubGlobal('fetch',fetcher)
  const api = new DemoApi(); await api.session()
  await api.saveAgent('reader', readerSpec)
  expect(fetcher.mock.calls[1][0]).toBe('/api/demo/agents')
  expect(fetcher.mock.calls[1][1]).toMatchObject({method:'POST',headers:{'X-CSRF-Token':'first'},body:JSON.stringify({id:'reader',spec:readerSpec})})
  await api.setAgentEnabled('reader', false)
  expect(fetcher.mock.calls[2][0]).toBe('/api/demo/agents/reader/enabled')
  expect(fetcher.mock.calls[2][1]).toMatchObject({body:JSON.stringify({enabled:false})})
  const created = {id:'t1',status:'ACTIVE' as const,version:1,goal:'g',pending:[],supplied:{},question:null,recentTools:[]}
  const start = vi.fn(async () => created), turn = vi.fn(async () => ({turn:null,verification:null,errorCode:null,message:'',operationId:null}))
  await createAndRunFirstTurn({start, turn}, 'g', [], () => {}, undefined, 'reader')
  expect(start).toHaveBeenCalledWith('g', [], 'reader')
  await createAndRunFirstTurn({start, turn}, 'g', [], () => {})
  expect(start).toHaveBeenLastCalledWith('g', [])
})

test('a global switch states who is affected and when it applies', () => {
  expect(impactMessage('工具','query_rules',['finance-assistant','finance-reader'],false))
    .toBe('停用工具 query_rules：将影响 2 个 Agent：finance-assistant、finance-reader。从下一轮对话起不再提供给模型，调用会返回 CAPABILITY_DISABLED；进行中的一轮不受影响。')
  expect(impactMessage('技能','bank-evidence',[],true)).toBe('启用技能 bank-evidence：当前没有 Agent 使用它，从下一轮对话起可用。')
})
test('skills can never grant saving and agents may consist of skills', () => {
  const skill = {name:'月度汇总',description:'',instructions:'先查发票再查流水',tools:['query_invoices']}
  expect(skillProblem('month-summary', skill)).toBeNull()
  expect(skillProblem('month-summary', {...skill, tools:['query_invoices','update_invoice']})).toContain('保存')
  expect(skillProblem('month-summary', {...skill, tools:[]})).toContain('工具')
  expect(skillProblem('Bad', skill)).toContain('编号')
  expect(agentProblem('purpose-checker', {...readerSpec, tools:[], skills:['purpose-gap-check']})).toBeNull()
  expect(agentProblem('purpose-checker', {...readerSpec, tools:[], skills:[]})).toContain('技能')
})
test('tool switch posts an explicit flag and the two capability errors read differently', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(session)).mockResolvedValueOnce(Response.json({name:'query_rules',enabled:false}))
  vi.stubGlobal('fetch',fetcher)
  const api = new DemoApi(); await api.session()
  await api.setToolEnabled('query_rules', false)
  expect(fetcher.mock.calls[1][0]).toBe('/api/demo/capabilities/tools/query_rules/enabled')
  expect(fetcher.mock.calls[1][1]).toMatchObject({method:'POST',headers:{'X-CSRF-Token':'first'},body:JSON.stringify({enabled:false})})
  expect(friendlyError('CAPABILITY_NOT_GRANTED')).toContain('没有被授予')
  expect(friendlyError('CAPABILITY_DISABLED')).toContain('停用')
})

test('memory form explains scope rules before sending', () => {
  const today = '2026-11-18'
  expect(memoryProblem('PERSONAL','tone','先给结论','以后先给结论','',today)).toBeNull()
  expect(memoryProblem('PERSONAL','x','F002 的用途是办公','原话','',today)).toContain('个人记忆')
  expect(memoryProblem('PERSONAL','x','只看 c001','原话','',today)).toContain('个人记忆')
  expect(memoryProblem('USER_ENTERPRISE','x','F002 的用途是办公','原话','',today)).toBeNull()
  expect(memoryProblem('USER_ENTERPRISE','个人·x','y','原话','',today)).toContain('·')
  expect(memoryProblem('ENTERPRISE','x','y','原话','2026-11-17',today)).toContain('有效期')
  expect(memoryProblem('ENTERPRISE','x','y',' ','',today)).toContain('必须填写')
})
test('memory delete addresses one scope and name with CSRF', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(session)).mockResolvedValueOnce(Response.json([]))
  vi.stubGlobal('fetch',fetcher)
  const api = new DemoApi(); await api.session()
  await api.deleteMemory('PERSONAL','风格/简洁')
  expect(fetcher.mock.calls[1][0]).toBe('/api/demo/memories/PERSONAL/%E9%A3%8E%E6%A0%BC%2F%E7%AE%80%E6%B4%81')
  expect(fetcher.mock.calls[1][1]).toMatchObject({method:'DELETE',headers:{'X-CSRF-Token':'first'}})
})

test('compression summary reads only what the server sent to the model', () => {
  const modelInput = JSON.stringify({task:{}, preferences:{}, conversation:{turns_total:29, omitted_turns:{count:25},
    key_objects:{latest_month:{value:'八月',turn:10,said:'不对，是八月'}, months_mentioned:['九月','八月'], ids_mentioned:['C001','F003']},
    earlier_constraints:[{turn:3,user:'只看九月的发票'},{turn:'x',user:'bad'}], recent_turns:[]}})
  expect(contextSummary(modelInput)).toEqual({turnsTotal:29, omittedTurns:25, latestMonth:'八月', latestMonthTurn:10,
    constraints:[{turn:3,user:'只看九月的发票'}], ids:['C001','F003']})
  expect(contextSummary(JSON.stringify({task:{}}))).toBeNull()
  expect(contextSummary('not json')).toBeNull()
  expect(contextSummary(null)).toBeNull()
})
