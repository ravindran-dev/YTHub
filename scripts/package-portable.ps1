$ErrorActionPreference = 'Stop'
$taskRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskExe = Join-Path $taskRoot 'src-tauri\target\release\ythub.exe'
if (-not (Test-Path -LiteralPath $taskExe)) { throw 'Run npm run tauri build first.' }
$taskOutput = Join-Path $taskRoot 'artifacts\YTHub-portable'
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
Copy-Item -LiteralPath $taskExe -Destination (Join-Path $taskOutput 'YTHub.exe') -Force
Copy-Item -LiteralPath (Join-Path $taskRoot 'src-tauri\binaries') -Destination $taskOutput -Recurse -Force
foreach ($taskDoc in @('LICENSE','THIRD_PARTY_NOTICES.md','README.md')) { Copy-Item -LiteralPath (Join-Path $taskRoot $taskDoc) -Destination $taskOutput -Force }
Compress-Archive -Path (Join-Path $taskOutput '*') -DestinationPath (Join-Path $taskRoot 'artifacts\YTHub-portable.zip') -Force
Write-Host "Portable build: $taskOutput"
