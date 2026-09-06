# Stage the freshly built debug APK into dist/ for server-side update
# distribution. Mandatory after every APK build (phones update only via
# serverUrl + /apk/info, no GitHub fallback since v1.9.4).
# ASCII only. Usage: powershell -NoProfile -ExecutionPolicy Bypass -File stage_dist.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$apk = Join-Path $root 'android\app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) {
    Write-Output ('ERROR: not found: ' + $apk)
    exit 1
}
$gradle = Get-Content (Join-Path $root 'android\app\build.gradle') -Raw
if ($gradle -notmatch 'versionName\s+"([^"]+)"') {
    Write-Output 'ERROR: versionName not found in android/app/build.gradle'
    exit 1
}
$ver = 'v' + $Matches[1]
$dst = Join-Path $root 'dist\task-chain.apk'
$vfile = Join-Path $root 'dist\version.txt'
Copy-Item -LiteralPath $apk -Destination $dst -Force
[System.IO.File]::WriteAllText($vfile, $ver)
$size = (Get-Item -LiteralPath $dst).Length
Write-Output ("staged " + $ver + " -> dist/task-chain.apk (" + $size + " bytes)")
Write-Output 'verify: http://127.0.0.1:8000/apk/info should report this version'
