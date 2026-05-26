@echo off
setlocal EnableDelayedExpansion

set "ROOT=C:\proj\OauthProj"
set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot"
set "PATH=%JAVA_HOME%\bin;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Windows\System32;%PATH%"

:: Service definitions: name=port
set "SVC_discovery=8761"
set "SVC_oauth=8080"
set "SVC_gateway=8082"
set "SVC_reporting=8083"
set "SVC_portfolio=8084"
set "SVC_alert=8087"
set "SVC_results=8088"
set "SVC_results-python=8090"
set "SVC_sheet-import=8091"
set "SVC_announcement=8092"

:: Service folder mapping
set "DIR_discovery=discovery-service"
set "DIR_oauth=oauth"
set "DIR_gateway=gateway-service"
set "DIR_reporting=reporting-service"
set "DIR_portfolio=portfolio-service"
set "DIR_alert=alert-service"
set "DIR_results=results-service"
set "DIR_results-python=results-service-python"
set "DIR_sheet-import=sheet-import-service"
set "DIR_announcement=announcement-service"

:: Window title mapping
set "TITLE_discovery=Discovery-Service"
set "TITLE_oauth=OAuth-Service"
set "TITLE_gateway=Gateway-Service"
set "TITLE_reporting=Reporting-Service"
set "TITLE_portfolio=Portfolio-Service"
set "TITLE_alert=Alert-Service"
set "TITLE_results=Results-Service"
set "TITLE_results-python=Results-Python"
set "TITLE_sheet-import=Sheet-Import-Service"
set "TITLE_announcement=Announcement-Service"

:: Log file mapping
set "LOG_discovery=discovery"
set "LOG_oauth=oauth"
set "LOG_gateway=gateway"
set "LOG_reporting=reporting"
set "LOG_portfolio=portfolio"
set "LOG_alert=alert"
set "LOG_results=results"
set "LOG_results-python=results-python"
set "LOG_sheet-import=sheet-import"
set "LOG_announcement=announcement"

if "%~1"=="" goto :usage
if /i "%~1"=="--help" goto :usage
if /i "%~1"=="-h" goto :usage

:: Parse --servicename argument
set "INPUT=%~1"
set "SVC=%INPUT:--=%"

:: Validate service name
set "PORT=!SVC_%SVC%!"
if "%PORT%"=="" (
    echo [ERROR] Unknown service: %SVC%
    echo.
    goto :usage
)

set "SVCDIR=!DIR_%SVC%!"
set "SVCTITLE=!TITLE_%SVC%!"
set "SVCLOG=!LOG_%SVC%!"

echo ========================================
echo   Restarting %SVC% (port %PORT%)
echo ========================================
echo.

:: Step 1: Kill process on the port
echo [1/3] Stopping %SVC%...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr "LISTENING" ^| findstr ":%PORT% "') do (
    taskkill /F /PID %%a >nul 2>&1
)
timeout /t 2 /nobreak >nul

:: Verify port is free
netstat -ano | findstr "LISTENING" | findstr ":%PORT% " >nul 2>&1
if %errorlevel%==0 (
    echo [WARN] Port %PORT% still in use, force killing...
    for /f "tokens=5" %%a in ('netstat -ano ^| findstr "LISTENING" ^| findstr ":%PORT% "') do (
        taskkill /F /PID %%a >nul 2>&1
    )
    timeout /t 3 /nobreak >nul
)
echo         Stopped.

:: Step 2: Create launcher script and start
echo [2/3] Starting %SVC%...

if /i "%SVC%"=="results-python" (
    (
        echo @echo off
        echo call "%ROOT%\logs\_load_env.bat"
        echo cd /d "%ROOT%\%SVCDIR%"
        echo venv\Scripts\python.exe run.py ^> "%ROOT%\logs\%SVCLOG%.log" 2^>^&1
    ) > "%ROOT%\logs\_run_%SVCLOG%.bat"
) else (
    (
        echo @echo off
        echo call "%ROOT%\logs\_load_env.bat"
        echo set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot"
        echo set "PATH=%%JAVA_HOME%%\bin;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Windows\System32;%%PATH%%"
        echo cd /d "%ROOT%\%SVCDIR%"
        echo call mvnw.cmd spring-boot:run -DskipTests ^> "%ROOT%\logs\%SVCLOG%.log" 2^>^&1
    ) > "%ROOT%\logs\_run_%SVCLOG%.bat"
)

start "%SVCTITLE%" /min cmd /c "%ROOT%\logs\_run_%SVCLOG%.bat"

:: Step 3: Wait and verify
echo [3/3] Waiting for %SVC% to start...
set "READY=0"
for /l %%i in (1,1,12) do (
    if !READY!==0 (
        timeout /t 5 /nobreak >nul
        netstat -ano 2>nul | findstr "LISTENING" | findstr ":%PORT% " >nul 2>&1
        if !errorlevel!==0 (
            set "READY=1"
            echo.
            echo   [OK] %SVC% is running on port %PORT%
        )
    )
)

if %READY%==0 (
    echo.
    echo   [..] %SVC% still starting. Check logs\%SVCLOG%.log
)

echo.
echo Done. Log: logs\%SVCLOG%.log
goto :end

:usage
echo Usage: restart --^<service-name^>
echo.
echo Examples:
echo   restart --gateway
echo   restart --portfolio
echo   restart --oauth
echo.
echo Available services:
echo   --discovery       Eureka Discovery  (port 8761)
echo   --oauth           OAuth Service     (port 8080)
echo   --gateway         Gateway Service   (port 8082)
echo   --reporting       Reporting Service (port 8083)
echo   --portfolio       Portfolio Service (port 8084)
echo   --alert           Alert Service     (port 8087)
echo   --results         Results Java      (port 8088)
echo   --results-python  Results Python    (port 8090)
echo   --sheet-import    Sheet Import      (port 8091)
echo   --announcement    Announcement      (port 8092)

:end
endlocal
