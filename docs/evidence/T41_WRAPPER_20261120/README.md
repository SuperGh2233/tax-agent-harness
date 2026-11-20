# T41 Maven Wrapper 全新检出 — 证据说明

本目录是 `scripts/fresh_checkout_check.py --maven wrapper` 于 2026-11-20 21:13–21:15 针对提交 `892e217` 的运行结果归档：

- `results.json`：15 个步骤的逐项结果、耗时与每步输出末尾三行。
- `summary.md`：脚本自动生成的摘要（未人工修改）。

该次运行的前提正是本提交本身：`892e217` 把 `mvnw` 的文件模式从 100644 改为 100755，因此克隆出的 `mvnw` 可执行（`mvnw-executable` 通过），Wrapper 自行下载 Maven 3.9.9 并完成全部构建。

摘要末尾写有「每步完整输出见同目录的编号日志」，但 `*.log` 被仓库 `.gitignore` 排除，编号日志未入库，只留在运行时的输出目录。重跑：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) python3 scripts/fresh_checkout_check.py --maven wrapper --keep
```

关键结论不依赖日志正文：`results.json` 的 `mvnw-executable`、`maven-version`（Apache Maven 3.9.9）、`test-counts`（336/0/0/0）与 `jar-contains-ui` 记录足以复核。
