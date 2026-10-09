param([string]$Model = 'CPH2603', [string]$Serial = '')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$taskAdb = Join-Path $taskSdk 'platform-tools\adb.exe'
$taskApk = Join-Path $taskRoot 'artifacts\android\YTHub_1.0.2_android.apk'
if (-not (Test-Path -LiteralPath $taskApk)) { throw 'Build the APK with scripts/build-android.ps1 first.' }
if (-not $Serial) {
    $taskMatches = @()
    foreach ($taskLine in (& $taskAdb devices)) {
        if ($taskLine -match '^([^\s]+)\s+device$') {
            $taskCandidate = $Matches[1]
            $taskPhoneModel = (& $taskAdb -s $taskCandidate shell getprop ro.product.model).Trim()
            if ($taskPhoneModel -eq $Model) { $taskMatches += $taskCandidate }
        }
    }
    if ($taskMatches.Count -ne 1) { throw "Connect exactly one authorized $Model phone, or specify -Serial." }
    $Serial = $taskMatches[0]
}
$taskActualModel = (& $taskAdb -s $Serial shell getprop ro.product.model).Trim()
if ($taskActualModel -ne $Model) { throw "Selected device is $taskActualModel; expected $Model." }
& $taskAdb -s $Serial install -r $taskApk
if ($LASTEXITCODE -ne 0) { throw 'Installation failed. Check the phone authorization prompt.' }
& $taskAdb -s $Serial shell am start -n 'dev.ravindran.ythub.mobile/.MainActivity'
if ($LASTEXITCODE -ne 0) { throw 'Installed, but could not launch the activity.' }
Write-Output "YTHub installed and launched on $Model."
