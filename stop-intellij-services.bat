@echo off
echo Stopping all Java/Python Spring Boot services...
echo.

REM Kill by window title (matches start-intellij-services.bat titles)
taskkill /F /FI "WINDOWTITLE eq Discovery Service*" 2>nul
taskkill /F /FI "WINDOWTITLE eq OAuth Service*" 2>nul
taskkill /F /FI "WINDOWTITLE eq Gateway Service*" 2>nul
taskkill /F /FI "WINDOWTITLE eq Reporting Service*" 2>nul
taskkill /F /FI "WINDOWTITLE eq Portfolio Service*" 2>nul
taskkill /F /FI "WINDOWTITLE eq Alert Service*" 2>nul
taskkill /F /FI "WINDOWTITLE eq Results Service*" 2>nul
taskkill /F /FI "WINDOWTITLE eq Results Python*" 2>nul
taskkill /F /FI "WINDOWTITLE eq Sheet Import Service*" 2>nul
taskkill /F /FI "WINDOWTITLE eq Announcement Service*" 2>nul

echo.
echo Killing any remaining processes on service ports...
for %%p in (8761 8080 8082 8083 8084 8087 8088 8090 8091 8092) do (
    for /f "tokens=5" %%a in ('netstat -ano ^| findstr "LISTENING" ^| findstr ":%%p "') do (
        taskkill /F /PID %%a 2>nul && echo   Killed PID %%a on port %%p
    )
)

echo.
echo All service windows closed.
pause
