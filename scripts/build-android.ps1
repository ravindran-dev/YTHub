param([switch]$DeviceTests)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskAndroid = Join-Path $taskRoot 'android'
$taskTmp = Join-Path $taskAndroid '.gradle\tmp'
New-Item -ItemType Directory -Force -Path $taskTmp | Out-Null
$env:TEMP = $taskTmp
$env:TMP = $taskTmp
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$($taskTmp.Replace('\', '/'))"
if (-not $env:JAVA_HOME) {
    $taskJdk = 'C:\Program Files\Java\jdk-21'
    if (Test-Path -LiteralPath $taskJdk) { $env:JAVA_HOME = $taskJdk }
}
$taskGradleArgs = @('--no-daemon', ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug')
if ($DeviceTests) { $taskGradleArgs += ':app:connectedDebugAndroidTest' }
Push-Location -LiteralPath $taskAndroid
try {
    & '.\gradlew.bat' @taskGradleArgs
    if ($LASTEXITCODE -ne 0) { throw 'Android build or validation failed.' }
    $taskOutput = Join-Path $taskRoot 'artifacts\android'
    New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
    Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination (Join-Path $taskOutput 'YTHub_1.0.2_android.apk')
    Get-FileHash -LiteralPath (Join-Path $taskOutput 'YTHub_1.0.2_android.apk') -Algorithm SHA256
} finally { Pop-Location }
