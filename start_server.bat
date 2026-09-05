@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
set PYTHONIOENCODING=utf-8

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

".venv\Scripts\python.exe" run_server.py
pause
