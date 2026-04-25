@echo off
echo ========================================
echo   Stopping OAuthProj Services
echo ========================================
echo.

:: Kill by window title (matches the "start" titles from start-local.bat)
echo Stopping Discovery Service...
taskkill /F /FI "WINDOWTITLE eq Discovery-Service*" 2>nul
echo Stopping OAuth Service...
taskkill /F /FI "WINDOWTITLE eq OAuth-Service*" 2>nul
echo Stopping Gateway Service...
taskkill /F /FI "WINDOWTITLE eq Gateway-Service*" 2>nul
echo Stopping Reporting Service...
taskkill /F /FI "WINDOWTITLE eq Reporting-Service*" 2>nul
echo Stopping Portfolio Service...
taskkill /F /FI "WINDOWTITLE eq Portfolio-Service*" 2>nul
echo Stopping Alert Service...
taskkill /F /FI "WINDOWTITLE eq Alert-Service*" 2>nul
echo Stopping Results Service...
taskkill /F /FI "WINDOWTITLE eq Results-Service*" 2>nul
echo Stopping Results Python Service...
taskkill /F /FI "WINDOWTITLE eq Results-Python*" 2>nul
echo Stopping Sheet Import Service...
taskkill /F /FI "WINDOWTITLE eq Sheet-Import-Service*" 2>nul
echo Stopping Announcement Service...
taskkill /F /FI "WINDOWTITLE eq Announcement-Service*" 2>nul

echo.
echo Window processes stopped. Killing any remaining Java/Python on service ports...
echo.

:: Kill Java processes listening on our ports as fallback
for %%p in (8761 8080 8082 8083 8084 8087 8088 8091 8092) do (
    for /f "tokens=5" %%a in ('netstat -ano ^| findstr "LISTENING" ^| findstr ":%%p "') do (
        taskkill /F /PID %%a 2>nul && echo   Killed PID %%a on port %%p
    )
)

:: Kill Python on port 8090
for /f "tokens=5" %%a in ('netstat -ano ^| findstr "LISTENING" ^| findstr ":8090 "') do (
    taskkill /F /PID %%a 2>nul && echo   Killed PID %%a on port 8090
)

echo.
echo ========================================
echo   All services stopped.
echo ========================================
echo.
pause
