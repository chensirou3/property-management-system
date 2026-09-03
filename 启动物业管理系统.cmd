@echo off
chcp 65001 >nul
title 物业管理系统一键启动
cd /d "%~dp0"
echo 正在检查 Docker 并启动物业管理系统，请勿关闭此窗口……
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0启动物业管理系统.ps1"
if errorlevel 1 pause
