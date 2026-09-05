# Restart the task-chain dev server precisely (kill only uvicorn app.main processes).
$ErrorActionPreference = "SilentlyContinue"
Set-Location "D:\pythonitems\task-chain"

$procs = Get-CimInstance Win32_Process -Filter "Name='python.exe'"
foreach ($p in $procs) {
    if ($p.CommandLine -match "uvicorn" -and $p.CommandLine -match "app.main") {
        Stop-Process -Id $p.ProcessId -Force
        Write-Output ("killed old server pid " + $p.ProcessId)
    }
}
Start-Sleep -Milliseconds 800

if (Test-Path "server.log") { Remove-Item -Force "server.log" }
if (Test-Path "server.err.log") { Remove-Item -Force "server.err.log" }

$py = Join-Path (Get-Location) ".venv\Scripts\python.exe"
$p = Start-Process -FilePath $py `
    -ArgumentList "-m","uvicorn","app.main:app","--host","127.0.0.1","--port","8000","--log-level","warning" `
    -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput "server.log" -RedirectStandardError "server.err.log"
$p.Id | Out-File -Encoding ascii "server.pid"
Write-Output ("started server pid " + $p.Id)
Start-Sleep -Seconds 3
$port = netstat -ano | Select-String ":8000.*LISTENING"
Write-Output ("port8000: " + $port)
