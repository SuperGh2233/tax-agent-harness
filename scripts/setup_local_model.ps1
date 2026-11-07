# Store local model credentials outside the repository, in the workspace .tools directory.
# Run this in your own terminal. The key is prompted with hidden input and never printed.
param(
  [string]$Endpoint = 'https://qianfan.baidubce.com/v2/tokenplan/personal',
  [string]$Model = 'deepseek-v4.1-flash-1017',
  [string]$Protocol = 'openai-chat'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$workspace = Split-Path -Parent $root
$tools = Join-Path $workspace '.tools'
New-Item -ItemType Directory -Path $tools -Force | Out-Null
$out = Join-Path $tools 'model.env'

$enteredEndpoint = Read-Host "LLM_ENDPOINT [$Endpoint]"
if ([string]::IsNullOrWhiteSpace($enteredEndpoint)) { $enteredEndpoint = $Endpoint }
$enteredModel = Read-Host "LLM_MODEL [$Model]"
if ([string]::IsNullOrWhiteSpace($enteredModel)) { $enteredModel = $Model }
$enteredProtocol = Read-Host "LLM_PROTOCOL [$Protocol]"
if ([string]::IsNullOrWhiteSpace($enteredProtocol)) { $enteredProtocol = $Protocol }
$secure = Read-Host -AsSecureString 'LLM_API_KEY (hidden input)'
$key = [System.Net.NetworkCredential]::new('', $secure).Password
if ([string]::IsNullOrWhiteSpace($key)) { throw 'LLM_API_KEY must not be blank' }

@(
  "LLM_ENDPOINT=$enteredEndpoint",
  "LLM_MODEL=$enteredModel",
  "LLM_PROTOCOL=$enteredProtocol",
  "LLM_API_KEY=$key"
) | Set-Content -Path $out -Encoding UTF8

try { icacls $out /inheritance:r /grant:r "$($env:USERNAME):(R,W)" | Out-Null } catch { }
Write-Host "Wrote $out"
Write-Host 'The key was not printed. Do not paste it into chat, logs, screenshots or the repository.'
Write-Host 'Tell the agent that the local model configuration is ready.'