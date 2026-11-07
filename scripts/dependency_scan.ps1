# Dependency CVE scan using Maven's resolved runtime inventory + Google OSV.
# Run online before packaging; keep target/osv-report.json outside the repository.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$mvn = if (Test-Path (Join-Path $root 'mvnw.cmd')) { Join-Path $root 'mvnw.cmd' } else { 'mvn' }
& $mvn -B -ntp -DskipTests dependency:list "-DincludeScope=runtime" "-DoutputFile=target/dependency-list.txt"
if ($LASTEXITCODE -ne 0) { throw "dependency:list failed with exit code $LASTEXITCODE" }
& python (Join-Path $root 'scripts/osv_scan.py')
if ($LASTEXITCODE -ne 0) { throw "OSV scan failed with exit code $LASTEXITCODE" }