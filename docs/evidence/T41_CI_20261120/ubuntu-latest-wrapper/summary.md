# T39 全新检出与空缓存构建检查（自动生成，未经人工修改）

- 生成时间：2026-11-20 13:55:00 +0000；提交：`9ff7830f7803a17ab467ac7e3c0af8ab5bf2a9ef`（HEAD）
- 环境：openjdk version "21.0.13" 2026-10-20 LTS；Apache Maven 3.9.9 (8e8579a9e76f7d015ee5ec7bfcdc97d260186937)（仓库 Maven Wrapper）；linux x86_64
- 做法：从本仓库克隆该提交到新临时目录（只含已提交文件），Maven 仓库为该目录下的空目录，首次在线构建后再离线复跑。

| 步骤 | 结果 | 明细 | 耗时（秒） |
|---|---|---|---|
| java-21 | 通过 | openjdk version "21.0.13" 2026-10-20 LTS | — |
| clone | 通过 | exit 0 | 0.2 |
| checkout | 通过 | exit 0 | 0.0 |
| mvnw-executable | 通过 | 克隆出的 mvnw 可执行 | — |
| maven-version | 通过 | Apache Maven 3.9.9 (8e8579a9e76f7d015ee5ec7bfcdc97d260186937) | — |
| clean-tree | 通过 | 不应存在的目录 — | — |
| official-seed | 通过 | exit 0 | 0.1 |
| mvn-test-online | 通过 | exit 0 | 42.3 |
| test-counts | 通过 | {"tests": 336, "failures": 0, "errors": 0, "skipped": 0} | — |
| mvn-package-online | 通过 | exit 0 | 19.0 |
| jar-contains-ui | 通过 | BOOT-INF/classes/static/react-app/index.html 存在；JAR 56,793,086 字节 | — |
| initialize-data | 通过 | exit 0 | 0.7 |
| server-health | 通过 | /api/health {'status': 'UP'} | 2.9 |
| server-react-page | 通过 | /react-app/ HTTP 200 | 2.9 |
| mvn-package-offline | 通过 | exit 0 | 10.5 |

每步完整输出见同目录的编号日志。
