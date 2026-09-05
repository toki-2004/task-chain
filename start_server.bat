@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title TaskChain Server
set PYTHONIOENCODING=utf-8

netstat -ano | findstr /C:":8000 " | findstr "LISTENING" >nul
if not errorlevel 1 (
    echo [!] Port 8000 is already in use. The server may already be running.
    echo     Close this window, then run stop_server.bat if you want to restart.
    echo.
    pause
    exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
    echo [setup] first run: creating virtualenv...
    python -m venv .venv
    if errorlevel 1 (
        echo [error] python not found or venv failed. Please install Python 3.10+ first.
        pause
        exit /b 1
    )
    echo [setup] installing dependencies...
    ".venv\Scripts\python.exe" -m pip install -r requirements.txt -q
    if errorlevel 1 (
        echo [setup] default index failed, retry with tsinghua mirror...
        ".venv\Scripts\python.exe" -m pip install -r requirements.txt -q -i https://pypi.tuna.tsinghua.edu.cn/simple
    )
)

echo ==================================================
echo   TaskChain server is starting in this window.
echo   Keep this window open while using the system.
echo   Press Ctrl+C here (or run stop_server.bat) to stop.
echo ==================================================
".venv\Scripts\python.exe" run_server.py
echo.
echo [i] Server exited.
pause
