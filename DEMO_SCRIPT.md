# T12 本地演示与手动录屏

本地模拟身份，不是生产认证。Operator 固定映射官方 U001，Viewer 固定映射 U002；企业权限读取实际 SQLite permissions。所有演示 API 仅接受直接 loopback 请求，无 CORS、反向代理身份或浏览器 userId。不要开放外网，也不要设置 SERVER_ADDRESS 为非本地地址。

## 独立数据库与启动

仓库根目录执行，JDK21/Maven 3.6.3+，使用既有隔离依赖缓存。每次演示创建新目录，不复制或修改 data/harness.db，不覆盖任何既有数据库。`--database` 是已有初始化命令；运行时使用 `--taxagent.database`（也可用 TAXAGENT_DATABASE）。普通启动不会导入种子。

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" -DskipTests package
export T12_DEMO_DIR="$(mktemp -d "${TMPDIR:-/tmp}/taxagent-t12.XXXXXX")"
export T12_DEMO_DB="$T12_DEMO_DIR/demo.db"
java -jar target/harness-0.0.1-SNAPSHOT.jar --initialize-data --database="$T12_DEMO_DB"
# 无模型的安全预览：显式移除模型配置，避免意外真实云调用。
env -u LLM_ENDPOINT -u LLM_API_KEY -u LLM_MODEL \
  java -jar target/harness-0.0.1-SNAPSHOT.jar \
  --taxagent.database="$T12_DEMO_DB" --server.address=127.0.0.1 --server.port=8080
```

浏览器打开 http://127.0.0.1:8080/ 。也可使用：

```sh
env -u LLM_ENDPOINT -u LLM_API_KEY -u LLM_MODEL \
  mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" spring-boot:run \
  "-Dspring-boot.run.arguments=--taxagent.database=$T12_DEMO_DB --server.address=127.0.0.1 --server.port=8080"
```

Ctrl+C 停止；保留临时演示目录便于回查，本说明不自动清理文件。首次未缓存依赖时去掉 `-o` 下载。

## 模型状态与证据

未同时显式配置 LLM_ENDPOINT、LLM_API_KEY、LLM_MODEL 时，界面诚实显示 MODEL_NOT_CONFIGURED；无 Fake fallback、无财务写入、无云请求。可以演示身份、企业拒绝、创建/恢复任务、手动补充和错误审计，但不能声称已完成模型查询或写入。

本轮已获用户授权进行真实模型联调。请先在可信本机终端安全设置 `LLM_API_KEY`，再配置以下非凭证项，并用上述独立临时 DB 的 java 启动命令（去掉 env -u 三项）启动。不要打印或录制 key；`.env` 不自动加载。

```sh
# zsh 隐藏输入：key 不显示在屏幕，也不写入 shell 命令历史。
export LLM_ENDPOINT=https://dashscope.aliyuncs.com/apps/anthropic
export LLM_MODEL=qwen3.8-flash
read -rs "LLM_API_KEY?DashScope API key (hidden input): "; print
export LLM_API_KEY
java -jar target/harness-0.0.1-SNAPSHOT.jar \
  --taxagent.database="$T12_DEMO_DB" --server.address=127.0.0.1 --server.port=8080
# Ctrl+C 停止后清除当前 shell 中的模型配置。
unset LLM_API_KEY LLM_ENDPOINT LLM_MODEL
```

当前脚本使用 DashScope Anthropic-compatible Messages API：请求发往 base URL 后追加 `/v1/messages`，通过 `x-api-key` 认证，模型为 `qwen3.8-flash`。保留完整 `/responses` URL 对 GPT-6 Luna Max 的支持（`reasoning.effort=max`、`store=false`、`parallel_tool_calls=false`），以及完整 HTTPS `/chat/completions` 路径；协议按 endpoint 显式选择，不自动回退。思考/续接内容只留在当前请求的内部 history，不进入可见历史/审计。只用官方合成样本、全新临时 SQLite 与 loopback，禁止使用 data/harness.db 或真实财务数据。真实接入会发送消息及已授权读取的工具结果。本次尚未调用或验证真实云模型；真实 E2E 待本机安全配置轮换后的凭证后按已批准范围执行，不提供 Fake 的生产切换选项。

离线自动验收用测试专用 FakeLlmClient + 临时官方种子 SQLite，工具和写入是真实执行：

```sh
mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" -Dtest=DemoApiTest test
mvn -o -B -ntp -Dmaven.repo.local="$PWD/.m2/repository" test
```

## 建议演示顺序（约 4–5 分钟）

1. **身份与隔离（30 秒）**：指明“本地模拟身份，不是生产认证”。Viewer / C001 可查看，切换 Viewer / C002 展示 PERMISSION_DENIED。换 Operator / C001。
2. **查询（45 秒，需已配置真实模型）**：创建目标“查询 2026-09 发票和银行收支，再检查缺用途，等待用户提供用途后继续，不要自行猜测”，待补字段 `F002.purpose,F002.remarks`。发送“查询 C001 2026-09 发票合计、银行收入和支出，检查用途并注明规则版本，缺信息请追问”。展开实际工具时间线，展示 query_invoices / query_bank_entries / check_invoice_purpose 的真实返回。金额为整数分，银行与发票分别统计。初始 C001 发票合计 962206 分、银行收入 1300050 分、支出 718074 分，R002 2.0 缺用途 5 张；这些仅作初始核对，最终以当前 DB 为准。
3. **补充与恢复（60 秒）**：用户明确输入 `F002.purpose` 的值“办公室采购”和原话“F002 的实际用途是办公室采购”；点击“仅记录补充信息”。展示仅 TaskMemory.supplied 变化，发票尚未保存。刷新页面或退出、重新选同一身份企业，从任务列表恢复；展示原话和 WAITING_USER/ACTIVE 状态。其他身份或企业无此任务。补充不会由模型自动提取。
4. **精确保存（60 秒）**：在确认面板逐项看清 C001 / F002 / purpose / “办公室采购”。点击核对按钮，弹窗再次展示精确 JSON；先取消一次，证明无写入。再明确确认。模型须实际选择 update_invoice，后端仍精确比对授权。展示工具返回 verified=true 和独立 databaseReadback.verified=true；仅模型说“成功”不算。请模型重新查询当前发票及缺口，C001 初始未改库时缺口从 5 降至 4。若模型结束任务但未调用写工具，说明没有执行，不能把模型文本当保存证据；新尝试需重新发起任务和明确确认，不自动重试。
5. **审计与权限（30 秒）**：查看工具名、callId、月份/发票、字段名、operationId、时间和 verified，历史中没有完整流水、金额、用途/备注值。Viewer 只读，C002 拒绝。可切 Operator / C002 展示独立任务及规则 R003 1.0（初始缺口3张），不需要重复写入。

如果模型未配置，第2–4步只能展示 MODEL_NOT_CONFIGURED 与安全阻止，明确标为“未执行”，展示测试报告作为离线验收证据，不能剪辑成真实云模型成功演示。

## 保存失败或未知结果

工具超时、审计失败或断网时，保留页面 operationId；不要再次点保存或生成新操作重写。若财务已提交但 TaskMemory 保存失败，服务器调用 FinanceAgent.recover 只读核验，返回 TASK_STATE_SAVE_FAILED 和“财务写入已核验但任务状态保存失败”，并追加 RECOVERABLE_TASK_STATE_FAILURE 审计；任务状态仍可能为旧版本。若审计也失败，界面明确报审计失败，不能声明整体成功。检查独立 demo DB 和已有操作台账，恢复任务后重新查询。没有回读证据不宣称成功，recover 无记录也不意味着可盲目重试。

以下只读命令使用 Python 标准库，直接核对当前演示库与操作台账，不输出完整财务流水（保持 T12_DEMO_DB 环境变量）：

```sh
python3 -B -c 'import os, sqlite3; from pathlib import Path; p=Path(os.environ["T12_DEMO_DB"]); c=sqlite3.connect(p.as_uri()+"?mode=ro", uri=True); print(c.execute("SELECT enterprise_id,id,purpose,remarks FROM invoices WHERE enterprise_id=? AND id=?", ("C001","F002")).fetchone()); print(c.execute("SELECT operation_id,user_id,enterprise_id,invoice_id FROM invoice_operations").fetchall())'
```

## macOS 手动录屏

- 清理屏幕中与演示无关的敏感窗口，勿展示密钥、终端环境变量或个人信息。只录本地演示窗口及必要的独立 DB 回读。
- 按 **Shift–Command–5**，选择“录制所选部分”，框选浏览器；“选项”选择保存位置、麦克风（需要讲解时）和鼠标点击显示，点击“录制”。结束点击菜单栏停止按钮或 Command–Control–Esc。
- 或打开 **QuickTime Player → 文件 → 新建屏幕录制**，选择区域、音频后开始，菜单栏停止；按需修剪并另存 MOV。
- 建议≤5分钟；预演后再正式录制，写入只在新建独立库上演示一次。播放回看角色标识、弹窗精确值、真实工具结果、verified 回读、审计是否清晰，确认音画和总时长。
- 本任务提供脚本和录制准备，不自动操作系统录屏，也不声称已生成视频。录制文件保存在自行指定目录，不加入 Git；打包/提交为 T13，本任务不执行。

## 当前真实测试目标：DashScope Messages
当前目标为 `LLM_ENDPOINT=https://dashscope.aliyuncs.com/apps/anthropic`、`LLM_MODEL=qwen3.8-flash`；独立适配器追加 `/v1/messages`，使用 x-api-key、max_tokens=8192、output_config.effort=medium，自动工具选择。保留 GPT-6 Luna Responses 和既有 Chat Completions，无自动回退。思考及签名仅作为内部续接状态，不进入结果或审计。Qwen 的 HTTP 与 AgentRuntime 每次调用上限均为60秒；其他模型仍为15秒，系统硬上限60秒。

此前粘贴的凭证已暴露，必须先在供应商控制台撤销；替换凭证仅在本机 zsh 隐藏输入，勿粘贴到聊天：
```zsh
export LLM_ENDPOINT=https://dashscope.aliyuncs.com/apps/anthropic
export LLM_MODEL=qwen3.8-flash
read -rs "LLM_API_KEY?输入已轮换的新密钥（隐藏）："; print
export LLM_API_KEY
# 完成后清除
unset LLM_API_KEY
```
用户已授权合成数据及独立临时 SQLite。此次仅允许 loopback HTTP stub，不访问外网、不操作任何 SQLite；真实 E2E 等待轮换密钥在本机配置。T13 保持 IN_PROGRESS。上述目标替代文档早先的 Luna 当前目标描述，Luna 支持继续保留。

注意：Qwen 的单次 HTTP 请求和 AgentRuntime 等待上限为60秒；其他模型的默认仍为15秒。尚未测得真实云端延迟，超过上限会返回超时。
