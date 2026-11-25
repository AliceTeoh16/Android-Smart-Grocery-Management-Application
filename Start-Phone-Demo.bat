@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\Start-PhoneDemo.ps1" -BuildApk
if errorlevel 1 (
    echo.
    echo Phone setup failed. Read the error above before using an older APK.
    pause
    exit /b 1
)
echo.
echo Read build\phone-demo\phone-instructions.txt for the phone connection steps.
pause
