// Development-only component harness. Not a production build entry and never calls REST.
import { createRoot } from 'react-dom/client'
import { useState } from 'react'
import { Review } from '../src/App'
import type { Approval } from '../src/api'
import '../src/style.css'

function Fixture() {
  const [approval, setApproval] = useState<Approval | null>(null)
  return <main className="content-page"><h1>仅 UI 测试 · 合成数据</h1><p>无后端调用，不执行财务保存。二次确认结果仅回显在此页面。</p><Review enterprise="C001" invoices={[{key:{enterprise_id:'C001',invoice_id:'F002'},invoice_date:'2026-09-03',seller:'合成测试企业',item:'测试项目',amount_cents:100000,purpose:'',remarks:''}]} disabled={approval !== null} onApprove={setApproval}/><pre aria-label="测试回调结果">{JSON.stringify(approval,null,2)}</pre></main>
}
createRoot(document.getElementById('root')!).render(<Fixture/> )
