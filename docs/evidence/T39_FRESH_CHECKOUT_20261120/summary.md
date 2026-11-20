# T39 全新检出与空缓存构建检查（自动生成，未经人工修改）

- 生成时间：2026-11-20 17:10:24 +0800；提交：`0c98ec4ff6bb8212f7dc0d693fbf8d875bda6382`（HEAD）
- 环境：openjdk version "21.0.12.1" 2026-08-18 LTS；Apache Maven 3.10.0 (c43a36b8d67be7e0805a411bc0898af1a51f5472)；darwin arm64
- 做法：从本仓库克隆该提交到新临时目录（只含已提交文件），Maven 仓库为该目录下的空目录，首次在线构建后再离线复跑。

| 步骤 | 结果 | 明细 | 耗时（秒） |
|---|---|---|---|
| java-21 | 通过 | openjdk version "21.0.12.1" 2026-08-18 LTS | — |
| clone | 通过 | exit 0 | 0.6 |
| checkout | 通过 | exit 0 | 0.0 |
| clean-tree | 通过 | 不应存在的目录 — | — |
| official-seed | 通过 | exit 0 | 0.2 |
| mvn-test-online | 通过 | exit 0 | 49.7 |
| test-counts | 通过 | {"tests": 336, "failures": 0, "errors": 0, "skipped": 0} | — |
| mvn-package-online | 通过 | exit 0 | 30.1 |
| jar-contains-ui | 通过 | BOOT-INF/classes/static/react-app/index.html 存在；JAR 56,789,931 字节 | — |
| initialize-data | 通过 | exit 0 | 0.6 |
| server-health | 通过 | /api/health {'status': 'UP'} | 1.7 |
| server-react-page | 通过 | /react-app/ HTTP 200 | 1.7 |
| mvn-package-offline | 通过 | exit 0 | 7.4 |

每步完整输出见同目录的编号日志。
