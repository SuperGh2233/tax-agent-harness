# 财税 Agent React 工作空间（T15）

独立 Vite + React 19 + TypeScript 前端。首页 → Agent 执行工作区 → 审核/结果；任务历史和真实审计从顶部导航进入。原生演示首页保持原样。

## 安装与开发

建议 Node 22.12+（本机验证版本见 T15 计划），首次安装需要 npm registry 网络权限。在 `frontend/` 执行：

```sh
npm install
npm run dev
# http://127.0.0.1:5173/react-app/
```

Spring Boot 默认在 `127.0.0.1:8080`。后端的启动、Java 21、Maven 隔离缓存见根 README。请为测试提供全新临时合成 SQLite，不能使用现有 `data/harness.db` 或真实财务数据，也不能对现有库使用 `--initialize-data`。

后端端口不同时：

```sh
T15_BACKEND_ORIGIN=http://127.0.0.1:18085 npm run dev
```

Vite 只监听 loopback，`/api` 代理仅允许 HTTP loopback 目标；代理修改 Host 和 Origin 为目标 origin，以配合已有 DemoSecurity 精确 Origin 检查，不添加 Forwarded 头。浏览器始终使用相对 `/api/demo` 和同源 Cookie，不需要 CORS，也不放宽后端安全规则。不要将开发代理暴露到公网。

## 检查与生产构建

```sh
npm run typecheck
npm run lint
npm test
npm run build
```

构建输出 `frontend/dist/`，资源 base 固定为 `/react-app/`；无 CDN、无内联脚本、无密钥。所有页面在单个入口内导航，不依赖后端 SPA fallback。`node_modules`、`dist`、`.verification` 被本目录忽略。

本次按授权不向 `src/main/resources/static/` 写入构建产物。后续部署可在单独审核的发布步骤将 dist 内容复制到 `static/react-app/`，然后重新打包；不要替换 `static/index.html`。无需新增 Java Controller。

也可在仓库根目录临时挂载外部资源，保留已有 classpath 首页：

```sh
mkdir -p frontend/.verification/public
# 仅首次执行；链接不存在时创建
ln -s ../../dist frontend/.verification/public/react-app
# DATABASE 必须是已经初始化的临时合成库绝对路径。
java -jar target/harness-0.0.1-SNAPSHOT.jar \
  --server.address=127.0.0.1 --server.port=18085 \
  "--taxagent.database=$DATABASE" \
  "--spring.web.resources.static-locations=classpath:/static/,file:$PWD/frontend/.verification/public/"
# 浏览 http://127.0.0.1:18085/react-app/index.html
```

现有 Spring Boot 静态映射不会自动为嵌套目录提供 welcome page：生产使用 **`/react-app/index.html`**；裸 `/react-app/` 的实测情况见计划。Vite 开发入口为 `/react-app/`。若上线必须支持裸目录地址，需要另行授权的服务器重定向/重写配置；本次不改 Java 或替换原首页。

## 组件与运行时选择

- Tailwind 4 加局部 CSS 实现海军蓝/石墨视觉。`src/ui.tsx` 是可审查的 shadcn 风格 Button/Dialog；Dialog 使用 Radix primitives 管理焦点、Escape、遮罩及移动 Drawer。使用原生 select/details/checkbox 保持键盘操作。
- `DemoApi` 是小型 typed REST Adapter；`Thread`、`Composer`、`ToolGroup` 对应 assistant-ui 的消息、输入和工具分组职责，工具明细来自真实 `history`。
- 核对了 [assistant-ui ExternalStoreRuntime](https://www.assistant-ui.com/docs/runtimes/custom/external-store)（2026-10-30；registry 当时 `@assistant-ui/react` 为 0.15.32）。该 runtime 可连接外部消息状态，但本项目只有整轮 REST 返回、持久化任务摘要，以及独立可信审批接口；接入还需维护消息/工具内容转换和审批能力映射。此次采用用户允许的自有组件路径，减少两套状态同步，不声称 assistant-ui API 不可用或不稳定。
- CopilotKit 仅借鉴人工介入模式，不安装运行时，后端不提供 AG-UI/CopilotKit 协议。不显示虚构思考、实时步骤或流式动画。
- 官方参考：[Radix Dialog](https://www.radix-ui.com/primitives/docs/components/dialog)、[Vite public base path](https://vite.dev/guide/build.html#public-base-path)。

## 数据与授权边界

`src/api.ts` 对照 DemoController/TaskMemory/AgentRuntime/ToolContract/DemoAudit：任务/context/TurnView 为 camelCase，`resultJson` 内工具数据为 snake_case。API 路由：GET/POST/DELETE session；GET/POST tasks；GET tasks/{id}；POST tasks/{id}/cancel `{version,confirmed:true}`；POST supplement `{version,statements}`；POST turn `{message,approval?}`；GET audit。所有请求 `credentials:same-origin`、`cache:no-store`、`X-CSRF-Token: session.csrfToken`，选择身份后立即使用轮换 token。

首页发布新任务后，先创建独立任务 ID，再以原目标发起**一次无审批首轮 turn**；模型执行或任务列表刷新失败不会抹去已创建的任务，也不会自动重试。SQLite `one_open_agent_task` 约束同一用户/企业只能同时有一项 ACTIVE / WAITING_USER 任务；首页展示现有任务并可继续、刷新或**二次确认后取消**，服务端对取消强制当前版本、可信会话、CSRF、确认标志和审计。取消不删除历史、不回滚业务写入；未核验 operationId 时前端禁止取消。发送消息与结构化补充均不授权财务写入。恢复只能恢复后端保留的任务、待办及历史追问/摘要，后端没有完整跨轮聊天查询接口，因此不伪造历史消息。补充事实后清除本轮发票证据，重新查询。

审核只能选择本轮实际 query_invoices 的已验证结构数据（并校验当前企业），后续写入会使之前查询值失效；不解析模型 prose 为变更或证据。逐项核对企业/票据/字段/旧值/精确新值 → 勾选 → 第二个确认对话框 → 单次 approval，绑定新 UUID operationId。仅 purpose/remarks，每次一张票一个字段，空白明确清空。只读身份禁用保存。

只根据 `TurnView.verification.verified` 显示 DB 回读已核验；FINISHED、模型文字和单独工具文字均不等同保存成功。未知或未核验结果保守锁住再次保存，保留 operationId 和 taskId 到 sessionStorage（不存金额、用途、备注或授权）；没有自动重试。即使任务状态/审计失败，仍单独展示真实 verification 和 errorCode。解除未核验状态需要人工在后端审计/数据库核查后处理，本次不增加恢复/重试 API。

审计只在展开时请求，原始工具数据默认折叠，金额保持整数分；界面不做财务计算。没有 localStorage 财务缓存、模型密钥或真实模型假回复。

## 验证范围

实际命令、浏览器检查、隔离库写入计数、原文件哈希复核及遗留项见 [T15 计划](../docs/plans/active/PLAN-20261030-t15-react-frontend.md)。测试中的合成响应仅用于浏览器/adapter 检查，不进入生产代码；真实模型和实际财务写入不在本次 smoke 范围。
