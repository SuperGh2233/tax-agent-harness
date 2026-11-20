# T42 Windows 8.3 短路径下的前端构建 — 证据

`frontend/vite.config.ts` 把 `root` 定为 `realpathSync.native(...)` 之后，GitHub Actions `windows-latest` 作业（run `44755130890`，提交 `0051750`）的完整通过结果。

- `summary.md` / `results.json`：脚本自动生成，14 项步骤全部通过（Windows 无 POSIX 的 `mvnw-executable`），`mvn-package-online` 111 秒、JAR 56,793,187 字节且含 `static/react-app/index.html`。
- `vite-root-line.txt`：构建日志里的那一行，直接说明问题与修复：

```
[vite] root C:\Users\runneradmin\AppData\Local\Temp\t39-fresh-...\source\frontend
         (cwd  C:\Users\RUNNER~1\AppData\Local\Temp\t39-fresh-...\source\frontend)
```

两者指向同一目录、字符串不同——这正是 Vite 算出 `../../../../../../../runneradmin/...` 的原因。`realpathSync`（JS 实现）不展开 8.3 短名，只有 `realpathSync.native` 会，所以第一版修复（`b4b9bd6`）无效、第二版（`0051750`）才生效。

编号日志（`*.log`）被 `.gitignore` 排除，未入库；重跑方式见 `REPRODUCE.md` 第 2 节，或在 Actions 页面下载该次运行的 artifact。
