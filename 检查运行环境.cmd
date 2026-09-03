@echo off
chcp 65001 >nul
title 物业管理系统运行环境检查
cd /d "%~dp0"
echo 正在检查 Docker、磁盘位置、端口和配置……
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0启动物业管理系统.ps1" -CheckOnly -NoBrowser
if errorlevel 1 pause
