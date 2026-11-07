# Offline package path for judges: use an already-built frontend/dist and a warm Maven cache.
# The normal package path (without -Dfrontend.skip) downloads Node/npm and runs npm ci online.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $root 'frontend/dist/index.html'))) {
    throw 'frontend/dist is missing. Run an online "mvn -DskipTests package" once first, or use the prebuilt JAR.'
}
$mvn = if (Test-Path (Join-Path $root 'mvnw.cmd')) { Join-Path $root 'mvnw.cmd' } else { 'mvn' }
& $mvn -o -B -ntp -DskipTests -Dfrontend.skip=true package
if ($LASTEXITCODE -ne 0) { throw "offline package failed with exit code $LASTEXITCODE" }
Write-Host 'Offline package complete. The JAR contains the existing frontend/dist assets.'