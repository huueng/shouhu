@echo off
chcp 65001 >nul
rem 老人守护 · 电脑端一键部署（双击运行 = 只看状态）
rem 常用参数：-All 全新机全流程；-Permissions 只补权限；-SetHome 设默认桌面；-Unlock 应急解锁
setlocal
set PS=powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0provision.ps1"
if "%~1"=="" (
    %PS% -Check
) else (
    %PS% %*
)
echo.
pause
