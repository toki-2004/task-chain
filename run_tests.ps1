# Run full API test suite against a THROWAWAY database on port 8001.
# Never touches production data.db or the real admin password.
$ErrorActionPreference = "SilentlyContinue"
Set-Location "D:\pythonitems\task-chain"

$tmpDb = Join-Path $env:TEMP "taskchain_test.db"
if (Test-Path $tmpDb) { Remove-Item -Force $tmpDb }
$wal = "$tmpDb-wal"; $shm = "$tmpDb-shm"
if (Test-Path $wal) { Remove-Item -Force $wal }
if (Test-Path $shm) { Remove-Item -Force $shm }

# kill leftover test server on 8001 / entry server on 9399
$procs = Get-CimInstance Win32_Process -Filter "Name='python.exe'"
foreach ($p in $procs) {
    if (($p.CommandLine -match "uvicorn" -and $p.CommandLine -match "8001") -or
        ($p.CommandLine -match "entry_server")) {
        Stop-Process -Id $p.ProcessId -Force
    }
}
Start-Sleep -Milliseconds 600

$env:TASKCHAIN_DB = $tmpDb
$env:TASKCHAIN_PORT = "8001"
$env:TASKCHAIN_DISCOVERY_PORT = "9876"
$py = Join-Path (Get-Location) ".venv\Scripts\python.exe"
# seed demo users/devices into the throwaway DB
& $py seed_demo.py | Out-Null
# start throwaway entry_server on 9399
$env:PORT = "9399"
$env:ENTRY_TOKEN = "testtoken"
$entry = Start-Process -FilePath $py `
    -ArgumentList "tunnel\entry_server.py" -WindowStyle Hidden -PassThru
Write-Output ("entry server pid " + $entry.Id)
Remove-Item Env:PORT -ErrorAction SilentlyContinue
Remove-Item Env:ENTRY_TOKEN -ErrorAction SilentlyContinue
$p = Start-Process -FilePath $py `
    -ArgumentList "-m","uvicorn","app.main:app","--host","127.0.0.1","--port","8001","--log-level","warning" `
    -WindowStyle Hidden -PassThru
Write-Output ("test server pid " + $p.Id)
Start-Sleep -Seconds 3

$env:TASKCHAIN_TEST_BASE = "http://127.0.0.1:8001"
$env:PYTHONIOENCODING = "utf-8"
$env:PYTHONUTF8 = "1"
& $py test_api.py
$code = $LASTEXITCODE

Stop-Process -Id $p.Id -Force
Stop-Process -Id $entry.Id -Force
Remove-Item -Force $tmpDb
if (Test-Path $wal) { Remove-Item -Force $wal }
if (Test-Path $shm) { Remove-Item -Force $shm }
Write-Output ("exit code: " + $code)
exit $code
