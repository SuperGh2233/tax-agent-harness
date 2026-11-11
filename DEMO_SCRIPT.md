# 演示与录屏脚本（2026-11-11 版）

本脚本按实测结论重写，可直接用于录制 ≤5 分钟演示视频。旧版第 3→4 段"补充后再保存"的同任务顺序会让任务结项、写入按钮禁用，录制必然失败；本版改为**先保存、后补充（补充拆为独立任务）**。

安全边界：本地模拟身份，不是生产认证。Operator 固定映射 U001，Viewer 固定映射 U002。演示 API 仅接受直接 loopback 请求，无 CORS、无反向代理身份。只用官方合成种子、全新临时 SQLite、127.0.0.1；禁止使用 `data/harness.db`、真实财务数据与外网地址。密钥只在录屏外的终端输入，**不要出现在画面里**。

## 一、录制前准备（每次录制都必须重做）

**1. 必须从全新库开始。** 演示数字会随写入漂移：同一个库先保存过 F002，再查缺口就只剩 4 张。混用旧库会让画面数字与文档对不上。

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
export DEMO_DIR="$(mktemp -d "${TMPDIR:-/tmp}/taxagent-rec.XXXXXX")"
export DEMO_DB="$DEMO_DIR/demo.db"
java -jar target/harness-0.0.1-SNAPSHOT.jar --initialize-data --database="$DEMO_DB"
```

**2. 记录基线（录屏前做一次，画面里不出现）。**

```sh
python3 -B - <<'PY'
import os, sqlite3
from pathlib import Path
c = sqlite3.connect(Path(os.environ["DEMO_DB"]).as_uri() + "?mode=ro", uri=True)
tables = {r[0] for r in c.execute("select name from sqlite_master where type='table'")}
print("invoices", c.execute("select count(*) from invoices").fetchone()[0])
print("bank_entries", c.execute("select count(*) from bank_entries").fetchone()[0])
print("invoice_operations", c.execute("select count(*) from invoice_operations").fetchone()[0]
      if "invoice_operations" in tables else "未创建（服务未启动，正常）")
print("C001 2026-09 缺用途", [r[0] for r in c.execute("""
    select id from invoices
     where enterprise_id='C001'
       and invoice_date>='2026-09-01' and invoice_date<'2026-10-01'
       and (purpose is null or trim(purpose)='')
     order by invoice_date, id""")])
PY
```

预期：`invoices 40` / `bank_entries 48` / 缺用途 `['F002','F004','F003','F006','F009']` 共 **5 张**；台账表此时通常"未创建"（服务启动时才建），服务起来后应为 0 行。若不是这个基线，直接换新库重来，不要在脏库上录。

**3. 清掉可能残留的操作锁。** 上一次保存失败会在浏览器留锁，换库重启也不会释放，会让新任务一直提示"保存请求需核查"。在浏览器控制台执行：

```js
localStorage.removeItem('taxagent-pending-operation-shared');
sessionStorage.removeItem('taxagent-pending-operation');
```

**4. 启动服务（保持这个终端，录制中不要重启）。** `.env` 不会自动加载，密钥必须显式注入：

```sh
read -rs "LLM_API_KEY?DashScope API key (hidden): "; print
export LLM_API_KEY
export LLM_ENDPOINT=https://dashscope.aliyuncs.com/apps/anthropic
export LLM_MODEL=qwen3.7-flash
java -jar target/harness-0.0.1-SNAPSHOT.jar \
  --taxagent.database="$DEMO_DB" --server.address=127.0.0.1 --server.port=18084
```

浏览器打开 `http://127.0.0.1:18084/`，根路径固定跳转 `/react-app/`。

未配置任何 `LLM_*` 时界面诚实显示 `MODEL_NOT_CONFIGURED`，只能演示身份、拒绝、建任务与错误审计，**不得**剪成"真实模型成功"。

## 二、六段录制脚本（实测总时长约 3 分钟，留白后 ≤5 分钟）

### 第 1 段 · 身份与隔离（约 25 秒）

- 开头点明"本地模拟身份，不是生产认证"
- 切 Viewer / C002 → 红框 `PERMISSION_DENIED`（企业权限越权被拒）
- 切回 Operator / C001

### 第 2 段 · 建任务与只读查询（约 25 秒，模型耗时 24.7 秒）

建任务，**待补字段只填 `F002.purpose` 一个**（填两个字段会让模型在保存轮多带参数，被 fail-closed 拒绝，见第五节）。目标一句话：

> 查询 2026-09 发票和银行收支，再检查缺用途，等待用户提供用途后继续，不要自行猜测

发送：

> 查询 C001 2026-09 发票合计、银行收入和支出，检查用途并注明规则版本，缺信息请追问。

展开"实际执行活动"，展示 4 个真实工具：`query_invoices`、`query_bank_entries`、`check_invoice_purpose`、`query_rules`。

**这一轮应当说出的数字（新建库）**：发票 10 张、合计 962206 分（9,622.06 元）；银行收入 1300050 分（13,000.50 元）；支出 718074 分（7,180.74 元）；依据 **R002 2.0**，缺用途 **5 张**（F002/F004/F003/F006/F009）；任务停在"等待补充"。

### 第 3 段 · 在"等待补充"状态下直接进入审核（约 15 秒）

不要先补充、不要先再发查询（会让任务结项、写入按钮变灰）。直接点"审核与结果"：

- 下拉应列出本轮查询的 10 张 2026-09 发票，日期是 `2026-09-01` 这样的字符串
- 选 `C001 / F002`、字段"用途 purpose"、当前值"（空）"、拟修改值"办公室采购"

### 第 4 段 · 二次确认，先取消一次（约 15 秒）

弹窗会复述企业/发票/字段/当前值/拟修改值 + `operationId`。先点"返回核对"取消，说明"取消不留痕"，库中零变化。

### 第 5 段 · 保存与回读核验（约 15 秒，模型耗时 13.2 秒）

重新发起确认（新 `operationId`）→ 顶部出现 **`verified=true · 数据库回读已核验`**，显示数据库用途"办公室采购"、备注"（空）"。

口播要点：这不是模型说成功，是后端写完再查一遍对上了；工具序列 `update_invoice` → `query_invoices` → `check_invoice_purpose`。

**此处数字变化**：发票表仍 40 行、流水仍 48 行；C001 缺用途 **5 → 4**；`invoice_operations` 1 行。

### 第 6 段 · 检查器与审计（约 40 秒）

- 执行计划 6 步全绿（含"保存并回读核验 verified=true"）
- 审计列表：`update_invoice · TOOL_RESULT` + ✓，只含字段名与 `operationId`，**不含金额与用途明文**
- 检查器"完整模型输入"可展开：系统提示、用户消息、工具 schema、历史条数

### 可选第 7 段 · 补充声明不改发票（约 20 秒）

新建一个任务，待补 `F004.purpose`；填字段、明确值、用户原话后点"仅记录信息"，界面提示"明确事实已记录，发票未保存"。展示 `F004.purpose` 仍为空、台账仍 1 行——**收到信息 ≠ 获得保存授权**。

## 三、数字口径表（录制与文档统一用这张表）

| 时点 | C001 2026-09 缺用途 | 台账 | 说明 |
|---|---|---|---|
| 新建库 | **5 张**（F002/F004/F003/F006/F009） | 0 行 | 唯一基准口径 |
| 保存 F002 后 | **4 张**（F004/F003/F006/F009） | 1 行 | 本脚本第 5 段之后 |
| 再保存 F009（非本脚本） | 3 张 | 2 行 | 仅出现在 API 测试记录里，录制不要引用 |

任何写在报告、截图说明、视频口播里的缺口数字，都必须带上"哪一步之后"。5 张和 4 张不矛盾，矛盾只会出现在没标时点的写法里。

## 四、录屏操作（macOS）

- 清理屏幕上的敏感窗口、终端环境变量、个人信息；只录浏览器窗口与必要的只读核对
- **Shift–Command–5** → "录制所选部分" → 框选浏览器 → 选项里勾选鼠标点击与麦克风 → 录制；或 QuickTime Player → 文件 → 新建屏幕录制
- 建议先不带录制走一遍（预演），确认模型有响应、审核页有发票、保存可点，再正式录
- 录完回看四点：身份标识是否清楚、弹窗精确值是否可读、`verified=true` 是否可见、总时长是否 ≤5 分钟
- 视频放仓库外，不加入 Git

## 五、两个必须避开的坑

1. **待补字段必须与要写的字段一一对齐。** 登记 `F002.purpose,F002.remarks` 时，模型会在保存轮把 `remarks` 一并提交，与批准载荷不一致，后端 fail-closed 拒绝（`CONFIRMATION_REQUIRED`，零写入）。安全行为正确，但镜头前会突然失败。**本脚本只登记 `F002.purpose`。**
2. **补充后再查询会让任务结项。** 补充会清空待补字段，任务推进；此时再发一轮查询，任务直接 `COMPLETED`，审核页写入按钮禁用。若必须先补充，就把保存与补充拆成两个任务，不要在同一任务里连演。

## 六、保存失败或结果未知

工具超时、审计失败或断网时，保留页面 `operationId`，**不要再次点保存**、不要生成新操作重写。若财务已提交但任务状态保存失败，服务器调用 `FinanceAgent.recover` 只读核验，返回 `TASK_STATE_SAVE_FAILED` 并追加 `RECOVERABLE_TASK_STATE_FAILURE` 审计。没有回读证据不宣称成功；`recover` 无记录也不等于可以盲目重试。

只读核对当前库（不打印完整流水）：

```sh
python3 -B - <<'PY'
import os, sqlite3
from pathlib import Path
c = sqlite3.connect(Path(os.environ["DEMO_DB"]).as_uri() + "?mode=ro", uri=True)
print(c.execute("select id,purpose,remarks from invoices where enterprise_id='C001' and id='F002'").fetchone())
print(c.execute("select count(*), max(operation_id) from invoice_operations").fetchone())
PY
```

## 七、模型与适配（现行口径）

- 模型：`qwen3.7-flash`，`LLM_ENDPOINT=https://dashscope.aliyuncs.com/apps/anthropic`，`LLM_PROTOCOL` 不填时按 endpoint 路径选择 → Anthropic Messages 适配器（追加 `/v1/messages`、`x-api-key`）
- 赛事材料未指定模型名，只要求可配置切换；换模型只改 `LLM_MODEL`/`LLM_ENDPOINT`，不硬编码
- 超时：Qwen 的 HTTP 与 AgentRuntime 上限均为 60 秒（含思考 token）；其他模型默认 15 秒，系统硬上限 60 秒
- 已知供应商行为：qwen 有时把结束协议发成 `finish` 工具调用，运行时已归一化为文本协议，仍保留"工具错误不得报成功""批准轮未核验不得结束"约束
- 思考/签名只留在内部续接 history，不进入可见历史与审计
