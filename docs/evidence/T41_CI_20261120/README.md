# T41 GitHub Actions 全新检出 — 证据说明

本目录是 `.github/workflows/fresh-checkout.yml` 首次全绿的运行（run `44748507398`，提交 `9ff7830`，2026-11-20 21:54–22:09）的四个作业产物，按平台分目录：

| 目录 | 平台 | Maven | 步骤 |
|---|---|---|---|
| `ubuntu-latest-wrapper/` | linux x86_64 | Wrapper 自取 3.9.9 | 15/15 通过 |
| `ubuntu-latest-maven-3.6.3/` | linux x86_64 | 从 Apache 归档下载 3.6.3（SHA-512 核对） | 15/15 通过 |
| `macos-latest-wrapper/` | darwin arm64 | Wrapper 自取 3.9.9 | 15/15 通过 |
| `windows-latest-wrapper/` | win32 AMD64 | Wrapper 自取 3.9.9 | 14/14 通过（无 POSIX 的 `mvnw-executable` 步） |

每个目录只有脚本自动生成的 `summary.md` 与 `results.json`；编号日志（`*.log`）被仓库 `.gitignore` 排除，需要时按 `REPRODUCE.md` 第 2 节的命令重跑，或在 Actions 页面下载对应 artifact。

前两次运行都只在 Windows 失败，暴露并修掉了两个只在 Windows 出现的问题：

1. `67b7a0e`：`AgentRuntimeTest.lockedToolTimesOutWithoutModelRetryOrEmptySuccess` 超时返回后被放弃的工具线程仍持有 `runtime.db`，JUnit 删除 `@TempDir` 时报“文件被另一进程占用”。测试改为等文件可删除（即句柄已释放）再结束。
2. `9ff7830`：runner 的 `%TEMP%` 是 8.3 短名 `C:\Users\RUNNER~1\...`，Node 按长名解析同一文件，Vite 因此产出绝对 chunk 名、`npm run build` 失败。检查脚本改用 `GetLongPathNameW` 展开后的长名目录克隆，未改前端构建。

仍未处理的 CI 提示：`actions/checkout@v4`、`actions/setup-python@v5`、`actions/upload-artifact@v4` 仍以 Node 20 为目标（被强制跑在 Node 24 上），不影响结果；`actions/setup-java` 已升到 v5。
