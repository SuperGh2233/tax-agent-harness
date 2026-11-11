# 演示流程重录记录（修正顺序版，2026-11-11）

- 环境：macOS / JDK 21.0.12.1 / 模型 `qwen3.7-flash`（DashScope Anthropic 兼容入口）
- 实例：`127.0.0.1:18086`，独立临时库 `/tmp/taxagent-rec2-OSGiZQ/demo.db`（官方合成种子，未触碰 `data/harness.db`）
- 基线：发票 40 / 银行流水 48 / `invoice_operations` 空；C001 缺用途 10 张；全表哈希 `ac39341442ea8715`
- 数字口径：本记录的所有数字**只对应本库、本时点**（新建库，未复用任何既有库）。同一天的 [TEST_REPORT](TEST_REPORT_20261111.md) 里出现的"缺 4 张 / 3 张"属于另一个库（先写入过 F002、F009），两者不矛盾，不可混用。新建库的 9 月基线与本节一致：**5 张**（F002/F004/F003/F006/F009）。
- 截图：`docs/evidence/demo-rerun-20261111-01 … 14`（14 张，按镜头顺序编号）

## 一、为什么改顺序

原 `DEMO_SCRIPT.md` 第 3 段「补充与恢复」→ 第 4 段「精确保存」在同一任务里连演，会踩到一条真实约束：

**补充把待补字段清空后，任务状态推进；再发起新一轮查询会直接结项（`COMPLETED`），此后同一任务的写入按钮被禁用（`disabled`），第 4 段无法继续。**

因此重录采用：**先保存、后补充**。保存发生在「等待补充（`WAITING_USER`）」状态下，此时任务仍开放、审核页能拿到本轮查询证据，写入通道可用（实测通过）。补充声明拆成独立的第二个任务，只演示「仅记录声明、不写入发票」。

## 二、修正后的六段（本机实测总时长 ≈ 3 分钟）

| # | 段落 | 实测耗时 | 画面要点 | 截图 |
|---|---|---|---|---|
| 1 | 身份与隔离 | ~25s | 弹窗三行提示「本地模拟身份，不是生产认证」；Viewer/C002 → 红框 `PERMISSION_DENIED`；换 Operator/C001 | 01、02 |
| 2 | 建任务与查询 | 24.7s | 目标一段话 + 待补字段 `F002.purpose`（单字段）；首轮 4 个只读工具；回复「还需要你确认这些信息：F002.purpose…我只记录你的声明，不会直接修改发票」 | 03、04、05 |
| 3 | 等待补充状态下直接审核保存 | 13.2s | 审核页列出本轮 10 张已查询发票；选 `C001 / F002`、字段 `用途 purpose`、数据库当前值「（空）」、拟修改值「办公室采购」 | 06 |
| 4 | 二次确认与取消 | ~10s | 弹窗复述企业/发票/字段/当前值/拟修改值 + `operationId`；先「返回核对」取消一次，库中零变化 | 07、08、09 |
| 5 | 保存与回读核验 | 13.2s | 新 `operationId` → 确认 → 顶部面板 `verified=true · 数据库回读已核验`，`数据库用途 办公室采购`、`备注（空）` | 10 |
| 6 | 检查器与审计 | ~40s | 执行计划 6 步全绿（第 5 步「保存并回读核验 verified=true，数据库回读一致」）；审计最新一条为 `update_invoice · TOOL_RESULT` + ✓ | 11、12 |
| 7（可选） | 补充声明不改发票 | 19.7s + 即时 | 新任务 `F004.purpose` → 追问 → 填字段/明确值/用户原话 → 「仅记录信息」→ 「明确事实已记录，发票未保存」 | 13、14 |

## 三、实测硬数据

| 项 | 值 |
|---|---|
| 任务 | `020af773`（保存链路，COMPLETED v2）、`b3545ac4`（补充链路，ACTIVE v2） |
| 首轮查询 | 07:31:40.191 → 07:32:04.894，**24.7s**，4 个工具（`query_invoices`/`query_bank_entries`/`check_invoice_purpose`/`query_rules`），终态 `NEEDS_INPUT` |
| 保存轮 | 07:32:37.910 → 07:32:51.142，**13.2s**，`update_invoice` → `query_invoices` → `check_invoice_purpose`，终态 `FINISHED` |
| operationId | `ec584656-59f8-4450-ab84-f2691cab39f8`（取消那次是 `f0304d78…`，未写入） |
| 发票表变化 | 40 行不变；哈希 `ac39341442ea8715` → `7aeebd55ab48987b`；C001 缺用途 **10 → 9**；仅 `F002.purpose` 由空变「办公室采购」 |
| `invoice_operations` | 1 行，`task_id` 已记录 |
| 审计 | 22 条；`update_invoice` 行只含字段名与 `operationId`；**含明文用途 0 行** |
| 补充链路 | `F004.purpose` 仍为空，`operations` 仍 1 行，缺用途仍 9 —— 只记录声明 |

## 四、两个必须知道的坑

1. **待补字段必须与要写的字段一一对齐。** 首轮录制用了 `F002.purpose,F002.remarks` 两个待补字段，模型在保存轮把 `remarks` 也一并提交，与批准载荷不一致，后端按 fail-closed 拒绝：`turn · ERROR · CONFIRMATION_REQUIRED`，零写入。这是正确的安全行为，但演示会突然失败。**结论：演示任务只登记本轮真正要写的那个字段。**
2. **保存请求失败会在浏览器留下操作锁。** 前端把未核验的 `operationId` 同时写入 `sessionStorage['taxagent-pending-operation']` 与 `localStorage['taxagent-pending-operation-shared']`，横幅持续提示「保存请求需核查」；换库重启后仍存在（`NOT_FOUND` 也不释放）。演示前若出现过失败，需要先走核查流程或清掉这两个键，否则新任务也会被锁住。

另注：模型对只读工具会重复调用（第二任务出现 2 次 `query_invoices` + 3 次 `query_rules`），属正常，不影响证据有效性，讲解时可直接说"只读工具可重复调用"。

## 五、建议对 DEMO_SCRIPT.md 的补丁（未改动原文）

- 第 3、4 段互换：先「精确保存」，再「补充声明」；补充单独起一个新任务。
- 第 2 段待补字段由 `F002.purpose,F002.remarks` 改为 `F002.purpose`（与第 4 段实际写入字段一致）。
- 第 4 段补一句前置：确认页面处于「等待补充」且审核页能列出本轮发票，否则先让 Agent 重新查询一轮。
- 录制前检查浏览器是否残留「保存请求需核查」横幅，若有先清理再录。

## 六、复现命令

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
R=$(mktemp -d /tmp/taxagent-rec-XXXXXX)
java -Djava.io.tmpdir="$R" -Dorg.sqlite.tmpdir="$R" \
  -jar target/harness-0.0.1-SNAPSHOT.jar --initialize-data --database="$R/demo.db"
set -a; . ./.env; set +a   # 模型配置只在进程内，不回显
java -Djava.io.tmpdir="$R" -Dorg.sqlite.tmpdir="$R" \
  -jar target/harness-0.0.1-SNAPSHOT.jar \
  --taxagent.database="$R/demo.db" --server.address=127.0.0.1 --server.port=18086
```

浏览器打开 `http://127.0.0.1:18086/`，按第二节顺序操作即可。
