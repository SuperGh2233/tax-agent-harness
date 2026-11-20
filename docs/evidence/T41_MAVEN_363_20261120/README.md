# T41 Maven 3.6.3（文档写明的最低版本）全新检出构建 — 证据说明

本目录归档 `python3 scripts/fresh_checkout_check.py --maven 3.6.3` 的运行结果：2026-11-20 20:55，用户 Mac（darwin arm64），JDK 21.0.12.1，检查对象为提交 `cf30578`。

- `results.json`：15 个步骤的逐项结果、耗时与每步输出末尾三行。
- `summary.md`：脚本自动生成的摘要，未经人工修改。

结论：从 Apache 归档下载 Maven 3.6.3，SHA-512 一致后用它从空仓库构建。在线 `test` 336/0/0/0，在线 `package`（含前端），JAR 内含 React 页面；新库初始化、loopback 健康检查与 React 页面正常；离线复跑通过。README、REPRODUCE、作品使用说明中「Maven 3.6.3+」的要求由此得到实测。

说明：
- 摘要里 Maven 版本两侧的 `[1m` / `[m` 是 Maven 3.6.3 输出的终端颜色码。之后的脚本已在识别版本前去掉颜色码，本归档保持原样。
- 同日 20:34 有一次在 JDK 25 下的运行（未切换 JDK），没有归档，记录见 `TASKS.md` T41。
- 编号日志被 `.gitignore` 的 `*.log` 排除，没有入库。
