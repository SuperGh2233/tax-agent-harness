# T40 真实 JAR 故障注入 — 证据说明

本目录归档 `scripts/fault_injection_check.py` 的运行结果。运行时间为 2026-11-20 18:02–18:04，在用户 Mac 上进行，环境为 JDK 21.0.12.1，所用 JAR 已含 T39 改动。

- `results.json`：8 个场景、40 项检查的逐项结果，机器可读。
- `summary.md`：脚本自动生成的摘要，未经人工修改。

做法：启动真实 JAR，用官方种子新建临时库，只走 loopback。模型换成脚本内的本机桩服务，使用 Anthropic Messages 协议，按脚本给出各种故障，不调用真实模型，也不需要密钥。

服务日志（`server-*.log`）被 `.gitignore` 的 `*.log` 排除，只保留在运行时的 `target/t40-fault-injection/` 中。其中 `server-01-timeout.log` 有一条 Tomcat「Error parsing HTTP request header」：18:02:57 有本机其他进程向该临时端口发送了 TLS 握手字节（`0x16 0x03 0x01 …`）。脚本只发明文 HTTP，这条连接被 Tomcat 直接拒绝，不影响任何检查。其余 9 份日志没有异常。

重跑：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) python3 scripts/fault_injection_check.py --jar target/harness-0.0.1-SNAPSHOT.jar
```
