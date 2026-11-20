# T43 CI 复跑 — 证据

提交 `220b26c` 后的 GitHub Actions 运行 `44767147980`：四个作业全绿，且注释区不再出现 Node 20 弃用提示。

- `summary.md` / `results.json`：Windows 作业的 14 项步骤（全部通过，336 项测试、JAR 56,793,187 字节含 `static/react-app/index.html`）。
- `vite-root-line.txt`：Windows 构建日志里的诊断行，证明收紧后的条件在 8.3 短名路径下仍会打印：

```
[vite] root C:\Users\runneradmin\AppData\Local\Temp\t39-fresh-...\source\frontend
         (cwd  C:\Users\RUNNER~1\AppData\Local\Temp\t39-fresh-...\source\frontend)
```

同一次运行的注释区只剩一条 macOS arm64 容量提示（非弃用）：

```
- Due to capacity constraints, jobs targeting macOS arm64 runners may experience longer queue times.
```

`checkout@v5`、`setup-python@v6`、`upload-artifact@v6` 三者在本次运行中均正常下载与执行（日志中可见 `Run actions/checkout@v5` 等步骤名），Node 20 的强制升级提示随之消失。编号日志被 `.gitignore` 排除，需要时在 Actions 页面下载该次 artifact。
