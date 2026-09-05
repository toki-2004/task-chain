# Stop the task-chain production server (port 8000) precisely.
# Kills only: run_server.py instances, or uvicorn app.main bound to port 8000.
# Never touches: test servers (8001), entry_server, or unrelated python apps.
$ErrorActionPreference = "SilentlyContinue"
Set-Location $PSScriptRoot

$killed = 0
$procs = Get-CimInstance Win32_Process -Filter "Name='python.exe'"
foreach ($p in $procs) {
    $c = $p.CommandLine
    $isProd = ($c -match "run_server\.py") -or
              (($c -match "uvicorn") -and ($c -match "app\.main") -and ($c -match "8000"))
    if ($isProd) {
        Stop-Process -Id $p.ProcessId -Force
        $killed++
        Write-Output ("stopped server pid " + $p.ProcessId)
    }
}
Start-Sleep -Milliseconds 800

$listen = netstat -ano | Select-String ":8000\s.*LISTENING"
if ($listen) {
    Write-Output "WARNING: port 8000 is still in use:"
    Write-Output $listen
    exit 1
}
Write-Output ("OK. Server stopped (" + $killed + " process/es), port 8000 is free.")
