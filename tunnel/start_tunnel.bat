@echo off
chcp 65001 >nul
cd /d "%~dp0"

if not exist "frpc.exe" (
    echo [!] frpc.exe not found in this folder.
    echo     Download from: https://github.com/fatedier/frp/releases
    echo     (frp_x.x.x_windows_amd64.zip, extract frpc.exe here)
    pause
    exit /b 1
)
if not exist "frpc.toml" (
    echo [!] frpc.toml not found. Copy frpc.example.toml to frpc.toml and edit it first.
    pause
    exit /b 1
)

echo [ok] starting frpc tunnel ... Ctrl+C to stop
frpc.exe -c frpc.toml
pause
