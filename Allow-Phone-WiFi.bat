@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\Enable-PhoneDemoFirewall.ps1"
echo.
echo Firewall result: build\phone-demo\firewall-result.json
pause
