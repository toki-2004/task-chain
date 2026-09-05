@echo off
chcp 65001 >nul
cd /d "%~dp0"
title TaskChain - Stop Server
echo Stopping task-chain server...
powershell -NoProfile -ExecutionPolicy Bypass -File "stop_server.ps1"
echo.
pause
