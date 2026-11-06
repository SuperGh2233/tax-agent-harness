# Build the React workspace and stage it under the Spring static resources.
# The staged directory is gitignored; a source-only checkout keeps using the static page.
param(
  [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $root 'frontend'
$target = Join-Path $root 'src/main/resources/static/react-app'

Push-Location $frontend
try {
  if (-not $SkipInstall) { npm ci --no-audit --no-fund }
  npm run verify
} finally {
  Pop-Location
}

if (Test-Path $target) { Remove-Item $target -Recurse -Force }
New-Item -ItemType Directory -Path $target | Out-Null
Copy-Item (Join-Path $frontend 'dist/*') $target -Recurse -Force
Write-Host "Staged React build at $target"
Write-Host "Spring now serves it at /react-app/ and redirects / to /react-app/ when packaged."