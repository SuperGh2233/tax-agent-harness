# T45–T48 CI 复跑 — 证据

提交 `7d20c66` 推送后触发的 GitHub Actions 运行 [`44916473839`](https://github.com/SuperGh2233/tax-agent-harness/actions/runs/44916473839)：**四个作业全绿**，取代此前基于 `64e4a63` 的 CI 证据。

| 作业 | 结果 | 步骤 | 耗时 |
|---|---|---|---|
| fresh-checkout (windows-latest, wrapper) | 通过 | 14/14 | 5m41s |
| fresh-checkout (ubuntu-latest, wrapper) | 通过 | 15/15 | 1m27s |
| fresh-checkout (macos-latest, wrapper) | 通过 | 15/15 | 1m55s |
| fresh-checkout (ubuntu-latest, 3.6.3) | 通过 | 15/15 | 1m41s |

四个作业的 `test-counts` 步骤一致：`{"tests": 351, "failures": 0, "errors": 0, "skipped": 0}`。
Windows 少一步是因为该平台不执行 `mvnw-executable`（可执行位）检查，其余步骤与 Linux/macOS 相同。

每个子目录是该作业上传的 artifact 内容（`summary.md` 为自动生成的逐步结果，`results.json` 为原始记录）；编号日志未纳入仓库，需要时在 Actions 页面下载。

注释区只有一条 macOS arm64 容量提示，非弃用告警：

```
- Due to capacity constraints, jobs targeting macOS arm64 runners may experience longer queue times.
```
