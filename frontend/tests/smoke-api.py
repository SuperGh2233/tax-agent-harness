"""Run against an explicitly isolated, unconfigured-model backend. Never writes invoices."""
import http.cookiejar
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

origin = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:18085'
assert origin == 'http://127.0.0.1:18085', 'Only the documented isolated smoke port is allowed'
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf = ''


def request(path, method='GET', data=None, expected=200):
    req = urllib.request.Request(origin + '/api/demo' + path,
        data=None if data is None else json.dumps(data).encode(), method=method,
        headers={'Content-Type': 'application/json', 'X-CSRF-Token': csrf, 'Origin': origin})
    try:
        response = opener.open(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    assert response.status == expected, (path, response.status)
    return json.load(response)


csrf = request('/session')['csrfToken']
first = csrf
selected = request('/session', 'POST', {'profile': 'operator', 'enterprise': 'C001'})
csrf = selected['csrfToken']
assert csrf != first
tasks = request('/tasks')
task = tasks[0] if tasks else request('/tasks', 'POST', {'goal': 'T15 isolated frontend smoke', 'pending': ['F002.purpose']})
context = request('/tasks/' + task['id'])
assert context['task']['id'] == task['id']
updated = request('/tasks/' + task['id'] + '/supplement', 'POST', {
    'version': context['task']['version'],
    'statements': {'F002.purpose': {'value': 'T15 synthetic fact only', 'userRequest': '仅补充合成测试信息，不保存发票'}}})
assert updated['supplied']['F002.purpose']['value'] == 'T15 synthetic fact only'
result = request('/tasks/' + task['id'] + '/turn', 'POST', {'message': '查询 2026-09 发票，不保存'})
assert result['errorCode'] == 'MODEL_NOT_CONFIGURED', result['errorCode']
assert result['turn']['result']['history'] == []
assert result['verification'] is None
events = request('/audit')
assert any(event['errorCode'] == 'MODEL_NOT_CONFIGURED' for event in events)
request('/session', 'DELETE')
assert request('/session')['profile'] == ''
print(json.dumps({'result': 'PASS', 'checks': ['session+token rotation', 'tasks', 'start/load', 'supplement',
    'turn MODEL_NOT_CONFIGURED', 'audit', 'logout'], 'auditEvents': len(events), 'financialApprovalRequests': 0}))
