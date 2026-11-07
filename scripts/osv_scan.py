import json
import re
import sys
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parent.parent
inventory = root / "target" / "dependency-list.txt"
report = root / "target" / "osv-report.json"
if not inventory.exists():
    raise SystemExit("run: mvn dependency:list -DincludeScope=runtime -DoutputFile=target/dependency-list.txt")

dependencies = []
seen = set()
pattern = re.compile(r"^\s+([^:\s]+):([^:\s]+):jar:([^:\s]+):(compile|runtime)")
for line in inventory.read_text(encoding="utf-8", errors="replace").splitlines():
    match = pattern.match(line)
    if not match:
        continue
    group, artifact, version, _ = match.groups()
    key = (group + ":" + artifact, version)
    if key not in seen:
        seen.add(key)
        dependencies.append({"name": key[0], "version": version})

queries = [{"package": {"name": dep["name"], "ecosystem": "Maven"}, "version": dep["version"]}
           for dep in dependencies]
vulnerabilities = []
suppressed = []
suppression_path = root / "scripts" / "osv_suppressions.json"
suppressions = json.loads(suppression_path.read_text(encoding="utf-8")) if suppression_path.exists() else {}
for offset in range(0, len(queries), 100):
    payload = json.dumps({"queries": queries[offset:offset + 100]}).encode("utf-8")
    request = urllib.request.Request(
        "https://api.osv.dev/v1/querybatch", data=payload,
        headers={"Content-Type": "application/json", "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        results = json.loads(response.read().decode("utf-8")).get("results", [])
    for dep, result in zip(dependencies[offset:offset + 100], results):
        for vuln in result.get("vulns", []):
            item = {
                "dependency": dep["name"], "version": dep["version"],
                "id": vuln.get("id"), "summary": vuln.get("summary", ""),
            }
            if item["id"] in suppressions:
                item["reason"] = suppressions[item["id"]]
                suppressed.append(item)
            else:
                vulnerabilities.append(item)

report.write_text(json.dumps({"dependencies": len(dependencies), "vulnerabilities": vulnerabilities,
                              "suppressed": suppressed}, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"OSV scanned {len(dependencies)} Maven runtime dependencies")
for item in vulnerabilities:
    print(f"VULNERABLE {item['dependency']} {item['version']} {item['id']} {item['summary']}")
for item in suppressed:
    print(f"SUPPRESSED {item['dependency']} {item['version']} {item['id']}: {item['reason']}")
print(f"OSV report: {report}")
sys.exit(1 if vulnerabilities else 0)