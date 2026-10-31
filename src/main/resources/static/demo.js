'use strict';
const $ = id => document.getElementById(id);
let csrf = '', active = null, selected = null, busy = false;
const pretty = value => JSON.stringify(value, null, 2);
const operationKey = 'taxagent-pending-operation';
function initialOperation() {
  const unknown = {id:'unknown', taskId:'unknown', unknown:true};
  try {
    const raw = sessionStorage.getItem(operationKey);
    if (raw === null) return null;
    const value = JSON.parse(raw);
    if (value && typeof value === 'object' && !Array.isArray(value)
        && typeof value.id === 'string' && value.id.trim()
        && typeof value.taskId === 'string' && value.taskId.trim() && value.unknown === true)
      return {id:value.id, taskId:value.taskId, unknown:true};
  } catch { /* Unreadable storage cannot prove there is no pending write. */ }
  return unknown;
}
let operation = initialOperation();
const operationWarning = document.createElement('p');
operationWarning.setAttribute('role', 'status');
$('verification').after(operationWarning);
function renderOperation() {
  operationWarning.textContent = operation
    ? '保存结果待核验 · operationId=' + operation.id + ' · taskId=' + operation.taskId + '；请核查审计与数据库，禁止重复保存。'
    : '';
  $('approve').disabled = busy || !!operation;
}
function rememberOperation(value) {
  // Do not unlock this page unless the persisted lock was successfully removed.
  if (!value) sessionStorage.removeItem(operationKey);
  operation = value;
  renderOperation();
  // If persistence fails, do not send a write that would lose its lock on reload.
  if (value) sessionStorage.setItem(operationKey, JSON.stringify(value));
}
renderOperation();
function entry(parent, label, content, style = '') {
  const node = document.createElement('div'); node.className = 'entry ' + style;
  node.textContent = label + '\n' + (typeof content === 'string' ? content : pretty(content));
  $(parent).append(node); return node;
}
async function api(path, method = 'GET', body) {
  const response = await fetch('/api/demo' + path, {method, credentials:'same-origin', cache:'no-store',
    headers:{'Content-Type':'application/json', 'X-CSRF-Token':csrf},
    body:body === undefined ? undefined : JSON.stringify(body)});
  const result = await response.json();
  if (!response.ok) throw new Error((result.errorCode || response.status) + '\n' + (result.message || '请求被拒绝'));
  return result;
}
async function action(work) {
  if (busy) return;
  busy = true; $('error').textContent = '';
  document.querySelectorAll('button').forEach(b => b.disabled = true);
  try { await work(); } catch (e) { $('error').textContent = e.message; }
  finally { busy = false; document.querySelectorAll('button').forEach(b => b.disabled = false); renderOperation(); }
}
function showTask(task) { active = task; $('task').textContent = pretty(task); $('status').textContent = task.status; }
function resetViews() {
  active = null; ['chat','tools','audit','tasks'].forEach(id => $(id).replaceChildren());
  $('task').textContent = '请选择或发起任务'; $('verification').textContent = '尚无已核验写入';
  $('status').textContent = '待选择任务';
}
function identity(session) {
  csrf = session.csrfToken; selected = session.profile ? session : null;
  $('identity').textContent = selected ? session.profile + ' / ' + session.enterprise + ' · 本地模拟身份' : '未选择身份';
  if (selected) { $('profile').value = session.profile; $('enterprise').value = session.enterprise; }
  $('writeEnterprise').value = selected ? selected.enterprise : ''; preview();
}
async function refresh() {
  if (!selected) return;
  const tasks = await api('/tasks'); $('tasks').replaceChildren();
  tasks.forEach(task => {
    const button = document.createElement('button'); button.className = 'secondary';
    button.textContent = task.status + ' · ' + task.goal;
    button.onclick = () => action(async () => {
      const context = await api('/tasks/' + encodeURIComponent(task.id)); showTask(context.task);
      $('tools').replaceChildren(); $('chat').replaceChildren(); $('verification').textContent = '恢复任务未授予任何写入授权；请重新查询当前数据';
      if (context.task.question) entry('chat','历史模型追问（恢复文本）',context.task.question);
    }); $('tasks').append(button);
  });
  const events = await api('/audit'); $('audit').replaceChildren();
  events.forEach(event => entry('audit', event.time + ' · ' + event.outcome, event, event.verified ? 'verified' : ''));
}
function requireTask() { if (!active || !selected) throw new Error('请先选择企业并发起或恢复任务'); }
function preview() {
  $('preview').textContent = pretty({enterprise:selected?.enterprise || '', invoice:$('invoice').value,
    field:$('field').value, exactValue:$('value').value});
}
function showTurn(result) {
  if (result.turn) {
    showTask(result.turn.task); const turn = result.turn.result;
    entry('chat', turn.status === 'ERROR' ? '执行错误' : '模型文本（不代表保存成功）', turn.text, turn.status === 'ERROR' ? 'failure' : '');
    $('tools').replaceChildren();
    turn.history.forEach((exchange, index) => {
      const details = document.createElement('details'), title = document.createElement('summary'), output = document.createElement('pre');
      let actual; try { actual = JSON.parse(exchange.resultJson); } catch { actual = exchange.resultJson; }
      title.textContent = (index + 1) + ' · ' + exchange.call.name + ' / ' + exchange.call.id;
      output.textContent = pretty({actualCall:exchange.call, actualResult:actual});
      details.append(title, output); $('tools').append(details);
    });
  }
  $('verification').textContent = result.message + '\n' + pretty({operationId:result.operationId, databaseReadback:result.verification});
  if (result.verification?.verified) entry('chat','数据库回读核验 verified=true',result.verification,'verified');
  if (result.errorCode) {
    const modelErrors = {
      MODEL_OUTPUT_LIMIT: '模型本轮输出达到上限，未执行不完整的工具调用。可以缩短问题后重试。',
      MODEL_RESPONSE_INVALID: '模型返回格式无法识别，本轮没有执行工具。',
      MODEL_TRANSPORT_ERROR: '连接模型服务失败，请检查网络和服务端地址。',
      MODEL_AUTH_FAILED: '模型服务认证失败，请检查本机配置的 API Key。',
      MODEL_RATE_LIMITED: '模型服务当前限流，请稍后重试。',
      MODEL_HTTP_ERROR: '模型服务返回异常状态；服务端错误正文已隐藏。',
      MODEL_TIMEOUT: '模型响应超时，本轮未完成。'
    };
    $('error').textContent = result.errorCode + '\n' + (result.errorCode === 'MODEL_NOT_CONFIGURED'
      ? '真实模型未配置：服务端必须显式设置 LLM_ENDPOINT / LLM_API_KEY / LLM_MODEL。此页面没有 Fake 模型。'
      : modelErrors[result.errorCode] || result.message);
  }
}
$('select').onclick = () => action(async () => {
  const session = await api('/session','POST',{profile:$('profile').value,enterprise:$('enterprise').value});
  resetViews(); identity(session); await refresh();
});
$('logout').onclick = () => action(async () => { await api('/session','DELETE'); resetViews(); identity(await api('/session')); });
$('refresh').onclick = () => action(refresh);
$('start').onclick = () => action(async () => {
  const pending = $('pending').value.split(',').map(x=>x.trim()).filter(Boolean);
  showTask(await api('/tasks','POST',{goal:$('goal').value,pending})); await refresh();
});
$('send').onclick = () => action(async () => {
  requireTask(); const message = $('message').value; entry('chat','用户输入（未授权财务写入）',message);
  showTurn(await api('/tasks/' + encodeURIComponent(active.id) + '/turn','POST',{message})); await refresh();
});
$('supplement').onclick = () => action(async () => {
  requireTask(); const statements = {[$('factKey').value]:{value:$('factValue').value,userRequest:$('factQuote').value}};
  showTask(await api('/tasks/' + encodeURIComponent(active.id) + '/supplement','POST',{version:active.version,statements}));
  entry('chat','用户明确补充（仅记录信息，不保存发票）',statements); await refresh();
});
['invoice','field','value'].forEach(id => $(id).addEventListener('input',preview));
$('approve').onclick = () => action(async () => {
  if (operation) throw new Error('已有待核验 operationId=' + operation.id + '，禁止再次保存。');
  requireTask();
  const invoiceId = $('invoice').value, changes = {[$('field').value]:$('value').value};
  const exact = {enterprise:selected.enterprise, invoiceId, changes};
  if (!window.confirm('请再次核对以下精确内容。点击确定表示现在保存（空白表示明确清空）。\n' + pretty(exact))) return;
  const operationId = crypto.randomUUID();
  rememberOperation({id:operationId, taskId:active.id, unknown:true});
  $('verification').textContent = '正在处理 operationId=' + operationId + '；未知结果请先检查审计和数据库，不要重复写入。';
  const result = await api('/tasks/' + encodeURIComponent(active.id) + '/turn','POST',{
    message:'用户在结构化确认面板明确要求现在保存以下精确修改，保存后请查询发票与用途缺口核对：' + pretty(exact),
    approval:{operationId,invoiceId,changes,confirmed:true}});
  if (result?.operationId === operationId && result?.verification?.verified === true) rememberOperation(null);
  showTurn(result); await refresh();
});
action(async () => { identity(await api('/session')); await refresh(); });
