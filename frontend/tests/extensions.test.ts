import { afterEach, expect, test, vi } from 'vitest'
import { amountCents, DemoApi, describeUpload, latestMonth, mappingProblem, MONTH_PATTERN, provenNotExecuted, scanProblem, verifiedBatchResult, verifiedWriteResult, verifyPendingOperation } from '../src/api'
import type { TurnView, UploadResult } from '../src/api'
import { initialOperation } from '../src/App'

afterEach(() => { vi.unstubAllGlobals() })

const batchId = '33333333-3333-4333-8333-333333333333'
const taskId = '22222222-2222-4222-8222-222222222222'
const op1 = '44444444-4444-4444-8444-444444444444'
const op2 = '55555555-5555-4555-8555-555555555555'
const verified = (invoiceId: string) => ({key:{enterpriseId:'C001',invoiceId},purpose:'p',remarks:'',verified:true})
const batchView: TurnView = {turn:null,verification:null,errorCode:null,message:'',operationId:batchId,
  batch:{results:[verified('F002'),verified('F004')],verified:true,review:[{key:{enterpriseId:'C001',invoiceId:'F004'},reasons:['PURPOSE_STILL_REQUIRED']}],note:''}}

test('T47 a batch releases the lock only with every invoice verified, in count, for this batch', () => {
  expect(verifiedBatchResult(batchView, batchId, [op1, op2])).toBe(true)
  expect(verifiedBatchResult(batchView, 'other', [op1, op2])).toBe(false)
  expect(verifiedBatchResult(batchView, batchId, [op1])).toBe(false)
  expect(verifiedBatchResult({...batchView, batch:{...batchView.batch!, verified:false}}, batchId, [op1, op2])).toBe(false)
  expect(verifiedBatchResult({...batchView, batch:{...batchView.batch!, results:[verified('F002'), {...verified('F004'), verified:false}]}}, batchId, [op1, op2])).toBe(false)
  expect(verifiedBatchResult({...batchView, batch:null}, batchId, [op1, op2])).toBe(false)
  // A single-save receipt never counts for a batch, and a batch receipt is never "proven not executed".
  expect(verifiedWriteResult(batchView, batchId)).toBe(false)
  expect(provenNotExecuted({...batchView, errorCode:'WRITE_NOT_EXECUTED'}, batchId)).toBe(false)
})

test('T47 stored batch locks keep their per-invoice ids; malformed item lists fail closed', () => {
  expect(initialOperation(() => JSON.stringify({id:batchId,taskId,unknown:true,items:[op1,op2]}))).toEqual({id:batchId,taskId,unknown:true,items:[op1,op2]})
  expect(initialOperation(() => JSON.stringify({id:batchId,taskId,unknown:true,items:[]}))).toEqual({id:'unknown',taskId:'unknown',unknown:true})
  expect(initialOperation(() => JSON.stringify({id:batchId,taskId,unknown:true,items:[1]}))).toEqual({id:'unknown',taskId:'unknown',unknown:true})
})

test('T47 batch verification checks every invoice and keeps the lock on the first that is not VERIFIED', async () => {
  const answers: Record<string, string> = {[op1]:'VERIFIED', [op2]:'NOT_FOUND'}
  const api = {verify: vi.fn(async (_task: string, id: string) => ({operationId:id, status:answers[id] as 'VERIFIED' | 'NOT_FOUND', verification:answers[id] === 'VERIFIED' ? verified('F002') : null}))}
  const clear = vi.fn()
  await expect(verifyPendingOperation(api, {id:batchId,taskId,items:[op1,op2]}, clear)).rejects.toThrow()
  expect(api.verify).toHaveBeenCalledTimes(2)
  expect(clear).not.toHaveBeenCalled()
  answers[op2] = 'VERIFIED'
  await verifyPendingOperation(api, {id:batchId,taskId,items:[op1,op2]}, clear)
  expect(clear).toHaveBeenCalledTimes(1)
  await expect(verifyPendingOperation(api, {id:batchId,taskId,items:['not-a-uuid']}, clear)).rejects.toThrow()
})

test('T46 mapping needs every required field once and no column twice', () => {
  expect(mappingProblem('INVOICE', {ID:0,DATE:1,SELLER:2,ITEM:3,AMOUNT:4})).toBeNull()
  expect(mappingProblem('INVOICE', {ID:0,DATE:1,SELLER:2,ITEM:3})).toContain('价税合计')
  expect(mappingProblem('BANK', {ID:0,DATE:1,DIRECTION:2,AMOUNT:3,COUNTERPARTY:3})).toContain('同一列')
})

test('T46 upload summary mentions tables waiting for a mapping, and the mapping is sent as its own part', async () => {
  const file = {id:'f1',enterpriseId:'C001',name:'陌生.xlsx',version:1,sha256:'ab',kind:'XLSX' as const,sizeBytes:10,uploadedBy:'U001',uploadedAt:'2026-11-21T10:00:00Z',knowledgeEnabled:true,latest:true}
  const result: UploadResult = {status:'IMPORTED',file,invoicesImported:0,bankEntriesImported:0,conflicts:[],skipped:[],errors:[],chunks:3,
    tables:[{sheet:'票据',headerRow:1,headers:['票号'],samples:[['F201']],suggestedKind:'INVOICE',suggested:{ID:0},reason:'UNRECOGNIZED_HEADER'}]}
  expect(describeUpload(result)).toContain('1 个表格未能识别列')
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(result))
  vi.stubGlobal('fetch', fetcher)
  await new DemoApi().upload(new File(['x'], '陌生.xlsx'), [{sheet:'票据',kind:'INVOICE',headerRow:1,columns:{ID:0,DATE:1,SELLER:2,ITEM:3,AMOUNT:4}}])
  const form = fetcher.mock.calls[0][1].body as FormData
  expect(JSON.parse(String(form.get('mapping')))).toEqual([{sheet:'票据',kind:'INVOICE',headerRow:1,columns:{ID:0,DATE:1,SELLER:2,ITEM:3,AMOUNT:4}}])
  fetcher.mockResolvedValueOnce(Response.json(result))
  await new DemoApi().upload(new File(['x'], '陌生.xlsx'))
  expect((fetcher.mock.calls[1][1].body as FormData).has('mapping')).toBe(false)
})

test('T48 a scan is confirmed only with complete fields and the amount typed twice', () => {
  const base = {invoiceId:'F301',invoiceDate:'2026-10-12',seller:'合成供应商',item:'打印服务',amount:'1,234.50',remarks:'',amountConfirmation:'1234.5'}
  expect(amountCents('1,234.50')).toBe(123450)
  expect(amountCents('1.234')).toBeNaN()
  expect(scanProblem(base)).toBeNull()
  expect(scanProblem({...base, amountConfirmation:'1243.50'})).toContain('不一致')
  expect(scanProblem({...base, invoiceDate:'2026/10/12'})).toContain('YYYY-MM-DD')
  expect(scanProblem({...base, seller:' '})).toContain('必须填写')
  expect(scanProblem({...base, invoiceId:'F 301'})).toContain('发票编号')
  expect(scanProblem({...base, amount:'0', amountConfirmation:'0'})).toContain('正数')
})

test('T48 scan API posts the file as multipart and the confirmation as JSON with CSRF', async () => {
  const session = {csrfToken:'first',profile:'operator',enterprise:'C001',identityNotice:''}
  const fetcher = vi.fn().mockResolvedValueOnce(Response.json(session)).mockResolvedValueOnce(Response.json({status:'MANUAL'}))
    .mockResolvedValueOnce(Response.json({status:'IMPORTED'}))
  vi.stubGlobal('fetch', fetcher)
  const api = new DemoApi(); await api.session()
  await api.scan(new File(['%PDF-'], '扫描.pdf'))
  expect(fetcher.mock.calls[1][0]).toBe('/api/demo/files/scan')
  expect(fetcher.mock.calls[1][1]).toMatchObject({method:'POST',headers:{'X-CSRF-Token':'first'}})
  expect((fetcher.mock.calls[1][1].body as FormData).get('file')).toBeInstanceOf(File)
  await api.confirmScan(batchId, {invoiceId:'F301',invoiceDate:'2026-10-12',seller:'s',item:'i',amount:'1.00',remarks:'',amountConfirmation:'1.00',confirmed:true})
  expect(fetcher.mock.calls[2][0]).toBe(`/api/demo/files/scan/${batchId}/confirm`)
  expect(JSON.parse(fetcher.mock.calls[2][1].body)).toMatchObject({invoiceId:'F301',confirmed:true})
})

test('T51 audit export returns the CSV with a UTF-8 BOM and reports a refusal by its error code', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(new Response('id,time\n1,t\n', {status:200, headers:{'Content-Type':'text/csv'}}))
    .mockResolvedValueOnce(Response.json({errorCode:'AUTHENTICATION_REQUIRED'}, {status:401}))
  vi.stubGlobal('fetch', fetcher)
  const api = new DemoApi()
  const blob = await api.exportAudit()
  expect(fetcher.mock.calls[0][0]).toBe('/api/demo/audit/export')
  const bytes = new Uint8Array(await blob.arrayBuffer())
  expect([...bytes.slice(0, 3)]).toEqual([0xEF, 0xBB, 0xBF])
  expect(new TextDecoder().decode(bytes.slice(3))).toBe('id,time\n1,t\n')
  await expect(api.exportAudit()).rejects.toMatchObject({code:'AUTHENTICATION_REQUIRED', status:401})
})

test('T51 the re-query month comes only from what the user named, latest first, and is never assumed', () => {
  expect(latestMonth(['查询 2026-09 发票与银行收支'])).toBe('2026-09')
  expect(latestMonth(['检查 2026年9月 用途', '再看 2026/10 的银行流水'])).toBe('2026-10')
  expect(latestMonth(['2026-09 的发票', '核对 2026-09-15 那张'])).toBe('2026-09')
  expect(latestMonth(['你好，请介绍一下你自己'])).toBeNull()
  expect(latestMonth(['2026-13 不是月份'])).toBeNull()
  expect(MONTH_PATTERN.test('2026-09')).toBe(true)
  expect(MONTH_PATTERN.test('2026-9')).toBe(false)
})
