# T39 全新检出与空缓存构建检查（自动生成，未经人工修改）

- 生成时间：2026-11-21 22:26:09 +0800；提交：`05bfcab38844d5ceeee0e66ddd91da6bfb0d7219`（HEAD）
- 环境：openjdk version "21.0.12.1" 2026-08-18 LTS；Apache Maven 3.6.3 (cecedd343002696d0abb50b32b541b8a6ba2883f)（下载 3.6.3）；darwin arm64
- 做法：从本仓库克隆该提交到新临时目录（只含已提交文件），Maven 仓库为该目录下的空目录，首次在线构建后再离线复跑。

| 步骤 | 结果 | 明细 | 耗时（秒） |
|---|---|---|---|
| java-21 | 通过 | openjdk version "21.0.12.1" 2026-08-18 LTS | — |
| clone | 通过 | exit 0 | 0.4 |
| checkout | 通过 | exit 0 | 0.0 |
| maven-download | 通过 | Apache archive，SHA-512 c35a1803a6e70a12… 一致 | 38.4 |
| maven-version | 通过 | Apache Maven 3.6.3 (cecedd343002696d0abb50b32b541b8a6ba2883f) | — |
| clean-tree | 通过 | 不应存在的目录 — | — |
| official-seed | 通过 | exit 0 | 0.2 |
| mvn-test-online | 通过 | exit 0 | 84.6 |
| test-counts | 通过 | {"tests": 351, "failures": 0, "errors": 0, "skipped": 0} | — |
| mvn-package-online | 通过 | exit 0 | 44.8 |
| jar-contains-ui | 通过 | BOOT-INF/classes/static/react-app/index.html 存在；JAR 56,852,683 字节 | — |
| initialize-data | 通过 | exit 0 | 0.5 |
| server-health | 通过 | /api/health {'status': 'UP'} | 1.1 |
| server-react-page | 通过 | /react-app/ HTTP 200 | 1.1 |
| mvn-package-offline | 通过 | exit 0 | 7.2 |

每步完整输出见同目录的编号日志。
