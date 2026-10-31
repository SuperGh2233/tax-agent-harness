# 独立复现说明

从仓库根目录执行。要求 JDK **21**、Maven **3.6.3+**、Python 3（仅标准库，用于官方种子核对），可写系统临时目录及允许本机端口。实测 macOS arm64 / Java 21.0.12.1 / Maven 3.10.0；其他操作系统未实测。依赖版本固定于 pom.xml：Spring Boot 3.5.16、SQLite JDBC 3.53.4.0。不要复制运行数据库或密钥。

## 1. 准备干净源码

新 checkout 或不含 .git 的纯源码副本均可；必须有 pom.xml、src/main、src/test、scripts/official_seed.py、完整官方材料目录及本文。官方 Excel/Word 是公开比赛模拟原件，不是本地财务导出。不要带入 data、.env、target、.m2、临时财务数据、录屏。当前 T12 尚未提交：单独 checkout 当前 HEAD 或 git archive HEAD 会漏掉 UI，交付前应人工核对文件清单，不能声称当前 HEAD 已含完整交付。

选择 JDK21（macOS 示例；Linux/Windows 设置 JAVA_HOME 为自己的 JDK21 路径）：

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mvn -version
export T13_REPO="$PWD/.m2/repository"
```

两条版本命令都必须显示 Java 21。路径含空格时保留引号；不修改全局 Maven 配置。

## 2. 首次在线构建与后续离线构建

全新 checkout / 纯源码副本首次没有缓存，需能访问 Maven Central；以下命令下载到该副本独立目录，不携带大体积依赖交付：

```sh
python3 -B scripts/official_seed.py --check
mvn -B -ntp "-Dmaven.repo.local=$T13_REPO" test
mvn -B -ntp "-Dmaven.repo.local=$T13_REPO" -DskipTests package
```

种子核对必须输出 `PASS: original hashes and all seed fields verified`。package 中跳过测试的前提是上一条完整测试成功。确保所需测试、package 和可选 run 插件都曾在线成功后，后续同一机器/同一缓存可离线执行：

```sh
mvn -o -B -ntp "-Dmaven.repo.local=$T13_REPO" test
mvn -o -B -ntp "-Dmaven.repo.local=$T13_REPO" -DskipTests package
```

离线缺依赖时停止，允许联网后重跑在线命令；不要把 `-o` 当成首次安装方式。JAR 启动不需要 Maven 或网络。可选 `mvn spring-boot:run` 首次在线时用下节命令去掉 `-o`；只验证 package 不代表 run 插件完整离线缓存已就绪。

## 3. 初始化全新独立数据库

```sh
export T13_DEMO_DIR="$(mktemp -d "${TMPDIR:-/tmp}/taxagent-release.XXXXXX")"
export T13_DEMO_DB="$T13_DEMO_DIR/demo.db"
java -jar target/harness-0.0.1-SNAPSHOT.jar --initialize-data --database="$T13_DEMO_DB"
```

初始化为 2 企业、2 用户、4 权限、40 发票、48 流水、5 规则版本。初始化参数是 `--database`；服务参数是 `--taxagent.database`。普通启动不会导入；重复初始化保留同键已有值，不能恢复被修改的数据。每次演示另建目录，勿指定已有 data/harness.db。

## 4. 安全启动、健康检查、UI

```sh
env -u LLM_ENDPOINT -u LLM_API_KEY -u LLM_MODEL \
  java -jar target/harness-0.0.1-SNAPSHOT.jar \
  "--taxagent.database=$T13_DEMO_DB" --server.address=127.0.0.1 --server.port=8080
```

另一个终端：

```sh
curl --fail --show-error --silent http://127.0.0.1:8080/api/health
# 预期 {"status":"UP"}；仅证明应用存活，不证明数据库/模型就绪。
curl --fail --show-error --silent -o /dev/null http://127.0.0.1:8080/demo.js
```

浏览器打开 http://127.0.0.1:8080/，显示“本地模拟身份，不是生产认证”。选择 Operator/C001，创建查询任务后发送消息，应明确显示 MODEL_NOT_CONFIGURED；这不是模型查询成功，不会回退 Fake。Viewer/C001 只读，Viewer/C002 拒绝。详细演示、精确确认、超时只读回查见 [DEMO_SCRIPT.md](DEMO_SCRIPT.md)。结束在启动终端 Ctrl+C，确认健康地址已无法连接；保留独立目录用于检查，不自动清理。

可选开发启动（与 JAR 二选一；确保同一终端保留 T13_DEMO_DB，路径含空格建议使用环境数据库设置）：

```sh
env -u LLM_ENDPOINT -u LLM_API_KEY -u LLM_MODEL TAXAGENT_DATABASE="$T13_DEMO_DB" \
  mvn -o -B -ntp "-Dmaven.repo.local=$T13_REPO" spring-boot:run \
  '-Dspring-boot.run.arguments=--server.address=127.0.0.1 --server.port=8080'
```

`.env` 不会自动加载；无需创建真实密钥文件。当前 T13 测试目标是 DashScope：`LLM_ENDPOINT=https://dashscope.aliyuncs.com/apps/anthropic`、`LLM_MODEL=qwen3.8-flash`，通过 Anthropic Messages API 调用。GPT-6 Luna Max 的 Responses 路径（`reasoning.effort=max`、`store=false`、`parallel_tool_calls=false`）及完整 HTTPS `/chat/completions` 路径仍保留，endpoint 决定协议，不自动回退。`LLM_API_KEY` 仅在可信本机终端安全输入；详细启动步骤见 DEMO_SCRIPT。本轮仅完成离线 stub 测试，没有真实云模型调用；真实 E2E 范围已获用户授权，待撤销已暴露的凭证并在本机配置轮换密钥后执行，只能使用官方合成样本、独立临时 SQLite 与 loopback，不得使用 data/harness.db 或真实财务数据。

## 5. 故障排查与证据

| 现象 | 处理 |
|---|---|
| release 21 不支持 | 两条版本命令检查 JAVA_HOME 和 PATH，重选 JDK21 |
| 离线依赖缺失 | 用同一隔离 repo 在线完成对应 Maven goal；不复制他人全局缓存作为交付 |
| 临时目录拒绝 / SQLite native library 失败 / Socket denied | 授予测试进程系统临时目录和本机端口权限后重跑；不是业务测试通过 |
| 端口占用 | 改 `--server.port=18083` 并同步浏览器 URL；不杀他人进程 |
| STORAGE_ERROR | 检查独立 DB 路径、初始化结果和可写权限，健康 UP 不代表 DB 正常 |
| MODEL_NOT_CONFIGURED | 无模型预览的预期安全结果；不得当作已完成三个模型场景 |
| CSRF / 会话拒绝 | 从同源页面重新选择身份；勿使用代理或跨域入口 |
| 保存超时或结果未知 | 保留 operationId，只读 recover/重新查询核对，禁止盲目重写，见演示脚本 |

完整测试报告位于 target/surefire-reports（忽略）；Fake 模型、临时官方模拟库及本机 HTTP stub 验证确定性链路。T10 测试生成 target/t10-evidence，包含模型输入、规则/记忆、工具调用及结果；人工审查去敏后另选关键记录作为提交附件，不直接打包 target。

## 本次实际证据与限制（2026-10-30）

临时纯源码 `/tmp/taxagent-t13-oor9i4rc/source` 含现有未提交 T12 文件，复制时无 data/.env/target/.m2。使用原仓库 `.m2/repository` 的既有隔离缓存（通过绝对路径引用，未复制缓存），JDK21 离线 test：**187/0/0/0，BUILD SUCCESS，12:22:41 +08:00**；离线 package 成功。种子全字段及原件哈希核对 PASS；新建 `/tmp/taxagent-t13-oor9i4rc/demo.db`。沙箱内测试187项中145 errors（临时目录/端口限制），获得沙箱外执行权限后全量通过；初始化也因 SQLite 临时目录限制在获准后重跑成功。

JAR 绑定127.0.0.1:18083，HTTP健康200/UP，HTML与JS/CSS可访问；真实浏览器显示本地模拟身份并恢复任务，未配置模型 turn 返回200/MODEL_NOT_CONFIGURED。只读核对新库2企业/2用户/4权限/40发票/48流水/5规则，invoice_operations=0。Ctrl+C停止后curl连接失败（退出7），确认服务停止。未调用真实模型。日志在临时目录 test-escalated.log、package.log、server.log，非提交附件。

**未验证：空缓存首次在线依赖下载、真正全新 Git checkout、其他平台和 Maven最低版本、真实模型、真实视频、最终压缩包。** 为避免不必要下载没有联网重建缓存。T13 因空缓存/全新 checkout 未证实保留 IN_PROGRESS；用户下一步在独立新 checkout（先完成代码整合）执行第2节在线命令，再离线复跑，补确切证据。录屏和提交材料按 [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) 人工完成。

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
