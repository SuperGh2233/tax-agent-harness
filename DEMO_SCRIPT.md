# 演示与录屏脚本（2026-11-12 版，5 分钟）

本文件是当前唯一的演示脚本。旧版长脚本与重复的 5 分钟版已合并到这里，避免同一份演示流程出现多个版本。历史实测记录仍见 `docs/evidence/`。

安全边界：本地模拟身份，不是生产认证。Operator 固定映射 U001，Viewer 固定映射 U002。演示 API 仅接受直接 loopback 请求，无 CORS、无反向代理身份。只用官方合成种子、全新临时 SQLite、127.0.0.1；禁止使用 `data/harness.db`、真实财务数据与外网地址。密钥只在录屏外的终端输入，不要出现在画面里。

## 一、讲解主线

一句话定位：模型负责理解和选择工具，程序负责权限、规则、金额、写入和回读，人负责最后的精确确认。

评委最该记住三个点：

- 权限拒绝由服务端完成，模型说一句话改不了身份。
- 保存成功只认 `verified=true` 和数据库回读，不认模型文字。
- 审计只记录字段名和 `operationId`，不记录金额和用途明文。

## 二、5 分钟时间轴与逐字讲稿

### 0:00 到 0:20，开场，停留在首页

画面：首页，顶部显示“本地演示工作空间”。

口播：

> 这是财税 Agent Harness。它要解决的不是让模型多会聊天，而是让模型在财税流程里可以被约束、被核验。模型负责理解和选择工具，金额、权限、规则、保存和回读由程序完成。

### 0:20 到 0:42，身份与权限隔离

画面：点右上角“选择企业 / 身份”，选“只读成员 U002”和“C002 乙企业”，提交，展示红色 `PERMISSION_DENIED`。再切回“操作员 U001”和“C001 甲企业”。

口播：

> 先看权限边界。这里是本地模拟身份，不是生产认证。我切到只读成员 U002，再选乙企业 C002。U002 对 C002 无权访问，服务端直接拒绝，返回 `PERMISSION_DENIED`。身份来自可信会话，不是模型参数，也不能用一句话改权限。切回操作员 U001、甲企业 C001。

### 0:42 到 1:10，建任务并发出首轮查询

画面：在目标框输入下面这段完整查询。展开“添加待补充字段（可选）”，只填 `F002.purpose`。点创建。工具会以流式逐条出现。

目标框原文，创建后它就是第一轮消息，首轮返回前不要再点发送：

> 查询 C001 2026-09 发票合计、银行收入和支出，检查发票用途并注明规则版本，缺信息请追问，不要猜测。

口播：

> 新建任务，待补字段只登记 F002.purpose。目标就是这一轮真实请求。创建后立即发起首轮，模型自己决定调用哪些工具。现在工具在逐条返回：发票、银行流水、用途检查、规则查询。流式只是展示，最终只认末帧 `done`。

这段如果模型超过 25 秒，不要切走，继续说：

> 模型调用有真实延迟，界面展示的是实际进度。超时会明确报错，不会伪造成功结果。

### 1:10 到 1:45，首轮结果

画面：展开“实际执行活动 · 4 个工具调用”。指一下金额、规则版本和缺用途编号。任务显示“等待补充”。

口播：

> 结果是 10 张发票，价税合计 9,622.06 元。银行收入 13,000.50 元，支出 7,180.74 元。依据当前启用的 R002 2.0，缺用途 5 张：F002、F004、F003、F006、F009。任务停在等待补充。这些数字来自工具结果，不是模型心算。规则版本也来自规则工具，停用的 1.0 不会被拿来用。

### 1:45 到 2:05，进入审核并选择精确修改

画面：点“审核与结果”。企业 / 发票选 `C001 / F002`，修改字段选“用途 purpose”，拟修改值填“办公室采购”。勾选核对声明，点“继续二次确认”。

口播：

> 进入审核。页面只列出本轮查询真实返回的发票，日期是字符串，金额是整数分。我选 C001 / F002，字段用途，数据库当前值为空，拟修改值为办公室采购。一次确认只绑定一张发票的一个字段。

### 2:05 到 2:25，二次确认后先取消

画面：弹窗显示企业、发票、字段、当前值、拟修改值和 `operationId`。点“返回核对”。

口播：

> 继续二次确认。弹窗复述企业、发票、字段、当前值、拟修改值和 operationId。先取消一次，返回核对。取消不写库，也不留操作记录。

### 2:25 到 2:55，重新确认，保存并回读

画面：重新勾选，点“继续二次确认”，这时会生成新的 `operationId`。点“确认，现在保存”。等待约 13 秒。出现 `verified=true · 数据库回读已核验`。

口播：

> 重新确认，这次真的保存。请求带着精确的 operationId 发给后端，后端再次校验权限、字段白名单和本轮授权。写入后不是直接报成功，而是在事务里重新读数据库。现在出现 verified=true，数据库回读已核验，用途是办公室采购，备注为空。缺用途从 5 张变成 4 张，操作台账只有 1 行。模型说成功不算数，回读对上才算。

### 2:55 到 3:30，检查器

画面：点右上角检查器图标。依次展开“执行计划”“上下文来源”“完整模型输入”，最后展开“实际执行活动”。

口播：

> 打开检查器。执行计划不是模型自述，而是后端根据任务状态和真实工具历史推导的。六步全部完成，保存步骤要求 verified=true。上下文来源分开展示：任务进度、长期偏好、规则引用、企业身份。完整模型输入可以看到系统提示、用户消息、工具 schema 和历史条数。上下文是裁剪过的，数据库才是权威。

### 3:30 到 4:05，审计

画面：顶部导航点“审计记录”，展开“展开真实审计记录”（列表按时间倒序，最新在最上面）。**`update_invoice · TOOL_RESULT` 不是第一条**——写入之后系统还会追加“查询核对”和“本轮结束”两条，通常排在第 4～5 条。找到它，展开详情，指出 `fields` = `purpose`、`operationId`、`verified` = `true`，没有金额和用途明文。

实测顺序（一次成功保存后，面板从上到下）：

| 位次 | 条目 | 含义 |
|---|---|---|
| 1 | `tasks · COMPLETED` | 任务状态保存 |
| 2 | `turn · FINISHED` | 本轮结束 |
| 3 | `query_invoices · TOOL_RESULT` | 保存后的数据库核对 |
| 4 | **`update_invoice · TOOL_RESULT`** | **要指的那条**，带 ✓ 核验标记 |
| 5 | `turn · REQUESTED` | 本轮开始 |

口播：

> 再看审计。这一条是 update_invoice 的工具结果，带核验标记。它记录字段名和 operationId，但不记录金额和用途明文。审计只追加，不允许修改或删除。即使任务状态保存失败，写入结果和错误码也会分开保留，不会把未知状态说成成功。

自查（录前必做）：**列表里如果完全没有 `update_invoice` 行，说明这次没有真正写入**（只跑了查询，或保存被 fail-closed 拒了），不能指着别的行说“已保存”。这时回到审核页重做保存，不要继续往下录。

### 4:05 到 4:35，记忆与授权边界

画面：返回工作台。任务已结束，页面显示“已恢复任务基本信息。历史工具记录仅供追溯，不代表当前数据库状态，也不会恢复任何写入授权”。可以指一下这句话。

口播：

> 最后看记忆和授权边界。历史任务可以恢复目标和上下文，但界面明确写着：历史工具记录仅供追溯，不代表当前数据库状态，也不会恢复任何写入授权。任务状态和用户明确提供的补充会持久化，但记忆只是上下文，不是本轮证据。要再次保存，必须重新查询、重新审核、重新确认。

### 4:35 到 5:00，收尾

画面：回到首页或停在核验面板，底部可以看到“受约束执行 · 可回读核验 · 模型文字不是财务执行证据”。

口播：

> 整套系统的分工很清楚。模型负责理解、追问和选择工具，程序负责权限、规则、金额、写入和回读，人负责最终的精确确认。模型文字、任务完成状态都不等于财务成功。所有验证只使用官方合成材料和独立临时数据库，服务只监听本机 loopback。这就是财税 Agent Harness：让 Agent 能做事，但每一步都有边界、有证据、可复核。

## 三、录制前准备（每次录制都重做）

### 1. 环境与构建

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mvn -version
```

两条版本命令都必须显示 Java 21。确认 JAR 是当前代码；如果之后改过代码，重新执行：

```sh
mvn -DskipTests package
```

### 2. 新建临时库

每次录制都新建库。不要用 `data/harness.db`，也不要在同一个库上重录。

```sh
export DEMO_DIR="$(mktemp -d "${TMPDIR:-/tmp}/taxagent-rec.XXXXXX")"
export DEMO_DB="$DEMO_DIR/demo.db"
java -jar target/harness-0.0.1-SNAPSHOT.jar --initialize-data --database="$DEMO_DB"
```

初始化参数是 `--database`，服务参数是 `--taxagent.database`，不能混用。

### 3. 核对基线

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

预期：`invoices 40`、`bank_entries 48`、缺用途 `['F002','F004','F003','F006','F009']` 共 5 张；台账表通常“未创建”，服务启动后应为 0 行。不是这个基线就换新库。

### 4. 清理浏览器残留锁

控制台执行：

```js
localStorage.removeItem('taxagent-pending-operation-shared');
sessionStorage.removeItem('taxagent-pending-operation');
```

如果出现过真实失败写入，不要只清锁，先走只读核验。

### 5. 启动服务

终端不要入镜。密钥用本机 `.env` 注入，不要手打：

```sh
set -a; . ./.env; set +a
java -jar target/harness-0.0.1-SNAPSHOT.jar \
  --taxagent.database="$DEMO_DB" --server.address=127.0.0.1 --server.port=18084
```

健康检查：

```sh
curl http://127.0.0.1:18084/api/health
```

浏览器打开 `http://127.0.0.1:18084/`，根路径固定跳转 `/react-app/`。未配置任何 `LLM_*` 时界面诚实显示 `MODEL_NOT_CONFIGURED`，不得剪成“真实模型成功”。

### 6. 预演与录屏

第一次完整走一遍用另一个临时库做预演。正式录制重新建库，避免预演写入改变缺用途数字。

- 清理屏幕上的敏感窗口、终端环境变量和个人信息，只录浏览器
- Shift 加 Command 加 5，选择“录制所选部分”，框选浏览器，勾选鼠标点击与麦克风
- 建议先不带录制预演，确认模型有响应、审核页有发票、保存可点，再正式录
- 录完回看四点：身份标识是否清楚、弹窗精确值是否可读、`verified=true` 是否可见、总时长是否 ≤5 分钟
- 视频放仓库外，不加入 Git

## 四、数字口径

| 时点 | C001 2026-09 缺用途 | `invoice_operations` | 说明 |
|---|---|---|---|
| 新建库 | 5 张：F002、F004、F003、F006、F009 | 0 行 | 唯一基准口径 |
| 保存 F002 用途后 | 4 张：F004、F003、F006、F009 | 1 行 | 本脚本第 5 段之后 |

首轮查询金额基线：发票 10 张，价税合计 9,622.06 元；银行收入 13,000.50 元，支出 7,180.74 元；依据 R002 2.0。

视频里只讲规则检查的“5 张变 4 张”。不要引用重录记录里的“10 张变 9 张”，那是另一个原始空白计数口径，容易和画面冲突。任何缺口数字都必须带时点。

## 五、失败处理

工具超时、审计失败或断网时，保留页面 `operationId`，不要再次点保存，不要生成新操作重写。若财务已提交但任务状态保存失败，服务器调用 `FinanceAgent.recover` 只读核验，返回 `TASK_STATE_SAVE_FAILED` 并追加 `RECOVERABLE_TASK_STATE_FAILURE` 审计。没有回读证据不宣称成功；`recover` 无记录也不等于可以盲目重试。

如果出现“保存请求需核查”横幅，不要直接清掉继续点保存。先确认是否真的发生过未知写入。正式录制建议直接换新库重来。

只读核对当前库，不打印完整流水：

```sh
python3 -B - <<'PY'
import os, sqlite3
from pathlib import Path
c = sqlite3.connect(Path(os.environ["DEMO_DB"]).as_uri() + "?mode=ro", uri=True)
print(c.execute("select id,purpose,remarks from invoices where enterprise_id='C001' and id='F002'").fetchone())
print(c.execute("select count(*), max(operation_id) from invoice_operations").fetchone())
PY
```

## 六、两个必须避开的坑

1. **待补字段必须与要写的字段一一对齐。** 登记 `F002.purpose,F002.remarks` 时，模型会在保存轮把 `remarks` 一并提交，与批准载荷不一致，后端 fail-closed 拒绝，返回 `CONFIRMATION_REQUIRED`，零写入。本脚本只登记 `F002.purpose`。
2. **补充后再查询会让任务结项。** 补充会清空待补字段，任务推进；此时再发一轮查询，任务直接 `COMPLETED`，审核页写入按钮禁用。若要演示补充，拆成独立任务，不要在同一任务里先补充再保存。

## 七、模型与适配（现行口径）

- 模型：`qwen3.7-flash`，`LLM_ENDPOINT=https://dashscope.aliyuncs.com/apps/anthropic`，`LLM_PROTOCOL` 不填时按 endpoint 路径选择 Anthropic Messages 适配器，追加 `/v1/messages`，使用 `x-api-key`
- 赛事材料未指定模型名，只要求可配置切换；换模型只改 `LLM_MODEL` 和 `LLM_ENDPOINT`，不硬编码
- 超时：Qwen 的 HTTP 与 AgentRuntime 上限均为 60 秒，包含思考 token；其他模型默认 15 秒，系统硬上限 60 秒
- 已知供应商行为：qwen 有时把结束协议发成 `finish` 工具调用，运行时已归一化为文本协议，仍保留“工具错误不得报成功”“批准轮未核验不得结束”约束
- 思考与签名只留在内部续接 history，不进入可见历史与审计
