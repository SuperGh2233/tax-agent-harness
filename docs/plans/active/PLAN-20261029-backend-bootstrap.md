# T02 后端启动与测试骨架

状态：completed（原路径保留以维持链接）；认领人：Coding Agent；日期：2026-10-29。

## 目标、范围与约束
建立 Java 21 + Spring Boot + Maven 工程，提供健康接口及 JUnit 5 真实 HTTP 集成测试。SQLite JDBC 仅为 T03 预备 runtime 依赖。
不导入数据、不建表、不实现业务功能，不引入 JPA、前端或向量库。T01 DONE、T02 已认领 IN_PROGRESS；保留原有未提交文档和材料。

## 接口与实施顺序
GET /api/health 返回 HTTP 200、application/json、精确 JSON `{"status":"UP"}`；仅表示应用可响应，不表示数据库或业务就绪，不改变数据。
先建立工程和测试，再验证启动与测试，最后更新台账。测试以随机端口启动完整应用，通过真实 HTTP 验证状态码、类型和内容。

## 验收
执行 `mvn test`、`mvn spring-boot:run`、`curl -i http://127.0.0.1:8080/api/health`。启动与测试均取得证据才标 DONE；实际命令与结果见 TASKS.md 的 T02 记录。

## 风险与回退
首次构建需要下载依赖；本机默认 Java 25，验收使用已安装 Java 21，仅进程级选择，不改系统或全局配置。缓存限定仓库内。
失败记录命令、阻塞及下一步；回退仅移除 T02 新增工程及恢复 T02 文档段落，不操作他人文件。

## 验证结果
JDK 21.0.12.1 / Maven 3.10.0：mvn test BUILD SUCCESS，1 test、0 failures、0 errors；独立 spring-boot:run 启动后 curl 得 HTTP 200 和约定 JSON，随后停止。沙箱临时目录失败及获准重跑过程见 TASKS.md T02 工作记录。T02 完成，无跨会话 handoff 需要；T03 未实施。
