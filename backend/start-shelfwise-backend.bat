@echo off
title ShelfWise Backend
cd /d C:\Users\Asus\Downloads\FYP\backend
netstat -ano | findstr ":8787" >nul
if not errorlevel 1 (
    echo.
    echo ShelfWise backend is already running on port 8787.
    echo You can close this window and use the existing backend.
    pause
    exit /b 0
)
echo Starting ShelfWise backend...
call npm start
echo.
echo Backend stopped. Read the message above for the cause.
pause
