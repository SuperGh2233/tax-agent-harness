# 依赖安全扫描（OSV）

## 运行

```powershell
.\scripts\dependency_scan.ps1
```

脚本两步：

1. `mvn dependency:list -DincludeScope=runtime` 生成已解析的运行时依赖清单；
2. `scripts/osv_scan.py` 调用 Google OSV `querybatch` API，输出 `target/osv-report.json`。

未抑制漏洞时退出码为 1；`scripts/osv_suppressions.json` 中的每条抑制都必须写明原因。

## 当前结果（2026-11-06）

- 扫描 35 个 Maven runtime 依赖。
- 已升级到修复版本：
  - Jackson `2.21.7`（原 `2.21.4`）
  - Tomcat `10.1.59`（原 `10.1.55`）
  - Log4j `2.25.5`（原 `2.24.3`）
- 0 个未抑制漏洞。
- 2 个已抑制：Spring Framework `6.2.19` 的 SSE/fragment 和 XSLT 相关通告，当前 6.2.x 无修复版本；本 demo 未暴露 SSE、fragment 渲染或 XSLT 视图，风险接受，下一次 Spring Boot 3.5.x 升级时复查。

## 边界

- OSV 结果是外部数据库快照，不构成零漏洞证明；发布前应重新运行并人工审阅报告。
- 不要把 `target/osv-report.json`、`target/dependency-list.txt` 或扫描缓存放入交付包。
- 未采用 OWASP dependency-check：其 NVD 全量更新在本地环境会长时间阻塞；OSV 扫描覆盖同一类 Maven CVE 查询且可复现。