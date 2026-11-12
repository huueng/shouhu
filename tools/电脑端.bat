@echo off
chcp 65001 >nul
rem 老人守护 · 电脑端：双击打开图形界面
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0电脑端.ps1"
if errorlevel 1 pause
