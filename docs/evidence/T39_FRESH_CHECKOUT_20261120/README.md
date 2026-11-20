# T39 全新检出与空缓存构建 — 证据说明

本目录是 `scripts/fresh_checkout_check.py` 于 2026-11-20 17:10 针对提交 `0c98ec4` 的运行结果归档：

- `results.json`：13 个步骤的逐项结果、耗时与每步输出末尾三行（机器可读）。
- `summary.md`：脚本自动生成的摘要（未人工修改）。

摘要末尾写有「每步完整输出见同目录的编号日志」，但 `*.log` 被仓库 `.gitignore` 排除（与 T38 证据目录一致），因此编号日志 `01-…` 至 `13-…` 未入库，只留在运行时的 `target/t39-fresh-checkout/`。如需完整输出，重跑：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) python3 scripts/fresh_checkout_check.py --keep
```

关键结论不依赖日志正文：`results.json` 的 `test-counts` 记录 336/0/0/0，`jar-contains-ui` 记录 JAR 内 React 页面路径与字节数，`server-health` / `server-react-page` 记录 HTTP 结果。
