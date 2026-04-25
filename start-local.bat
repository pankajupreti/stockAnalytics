@echo off
setlocal enableDelayedExpansion
echo ========================================
echo   Starting OAuthProj Services (Local)
echo ========================================
echo.
echo Make sure PostgreSQL and RabbitMQ are running.
echo.

set "ROOT=C:\proj\OauthProj"
set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot"
set "PATH=%JAVA_HOME%\bin;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Windows\System32;%PATH%"

:: Load secrets from .env.local (gitignored)
if exist "%ROOT%\.env.local" (
    echo [INFO] Loading environment from .env.local
    for /f "usebackq tokens=1,* delims==" %%A in ("%ROOT%\.env.local") do (
        set "LINE=%%A"
        if not "!LINE:~0,1!"=="#" if not "%%A"=="" set "%%A=%%B"
    )
) else (
    echo [WARN] .env.local not found! Copy .env.example to .env.local and fill in secrets.
)

:: Common env lines for each launcher script
set "ENV_LINE1=set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot""
set "ENV_LINE2=set "PATH=%%JAVA_HOME%%\bin;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Windows\System32;%%PATH%%"

:: Create logs directory
if not exist "%ROOT%\logs" mkdir "%ROOT%\logs"

:: Clear old logs
del /q "%ROOT%\logs\*.log" 2>nul
del /q "%ROOT%\logs\_run_*.bat" 2>nul
del /q "%ROOT%\logs\_load_env.bat" 2>nul

:: Write _load_env.bat with actual secret values (for child processes)
echo @echo off> "%ROOT%\logs\_load_env.bat"
if defined GOOGLE_CLIENT_ID echo set "GOOGLE_CLIENT_ID=!GOOGLE_CLIENT_ID!">> "%ROOT%\logs\_load_env.bat"
if defined GOOGLE_CLIENT_SECRET echo set "GOOGLE_CLIENT_SECRET=!GOOGLE_CLIENT_SECRET!">> "%ROOT%\logs\_load_env.bat"
if defined OPENAI_API_KEY echo set "OPENAI_API_KEY=!OPENAI_API_KEY!">> "%ROOT%\logs\_load_env.bat"

echo [INFO] Starting 10 services...
echo.

:: 1. Discovery Service (Eureka) - Port 8761 - must start first
echo [ 1/10] Starting Discovery Service (port 8761)...
(
    echo @echo off
    echo call "%ROOT%\logs\_load_env.bat"
    echo set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot"
    echo set "PATH=%%JAVA_HOME%%\bin;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Windows\System32;%%PATH%%"
    echo cd /d "%ROOT%\discovery-service"
    echo call mvnw.cmd spring-boot:run -DskipTests ^> "%ROOT%\logs\discovery.log" 2^>^&1
) > "%ROOT%\logs\_run_discovery.bat"
start "Discovery-Service" /min cmd /c "%ROOT%\logs\_run_discovery.bat"
echo         Waiting for Eureka to be ready...
timeout /t 25 /nobreak > nul

:: 2. OAuth Service - Port 8080
echo [ 2/10] Starting OAuth Service (port 8080)...
(
    echo @echo off
    echo call "%ROOT%\logs\_load_env.bat"
    echo set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot"
    echo set "PATH=%%JAVA_HOME%%\bin;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Windows\System32;%%PATH%%"
    echo cd /d "%ROOT%\oauth"
    echo call mvnw.cmd spring-boot:run -DskipTests ^> "%ROOT%\logs\oauth.log" 2^>^&1
) > "%ROOT%\logs\_run_oauth.bat"
start "OAuth-Service" /min cmd /c "%ROOT%\logs\_run_oauth.bat"
timeout /t 5 /nobreak > nul

:: 3. Gateway Service - Port 8082
echo [ 3/10] Starting Gateway Service (port 8082)...
(
    echo @echo off
    echo call "%ROOT%\logs\_load_env.bat"
    echo set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot"
    echo set "PATH=%%JAVA_HOME%%\bin;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Windows\System32;%%PATH%%"
    echo cd /d "%ROOT%\gateway-service"
    echo call mvnw.cmd spring-boot:run -DskipTests ^> "%ROOT%\logs\gateway.log" 2^>^&1
) > "%ROOT%\logs\_run_gateway.bat"
start "Gateway-Service" /min cmd /c "%ROOT%\logs\_run_gateway.bat"
timeout /t 3 /nobreak > nul

:: 4. Reporting Service - Port 8083
echo [ 4/10] Starting Reporting Service (port 8083)...
(
    echo @echo off
    echo call "%ROOT%\logs\_load_env.bat"
    echo set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot"
    echo set "PATH=%%JAVA_HOME%%\bin;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Windows\System32;%%PATH%%"
    echo cd /d "%ROOT%\reporting-service"
    echo call mvnw.cmd spring-boot:run -DskipTests ^> "%ROOT%\logs\reporting.log" 2^>^&1
) > "%ROOT%\logs\_run_reporting.bat"
start "Reporting-Service" /min cmd /c "%ROOT%\logs\_run_reporting.bat"
timeout /t 3 /nobreak > nul

:: 5. Portfolio Service - Port 8084
echo [ 5/10] Starting Portfolio Service (port 8084)...
(
    echo @echo off
    echo call "%ROOT%\logs\_load_env.bat"
    echo set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot"
    echo set "PATH=%%JAVA_HOME%%\bin;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Windows\System32;%%PATH%%"
    echo cd /d "%ROOT%\portfolio-service"
    echo call mvnw.cmd spring-boot:run -DskipTests ^> "%ROOT%\logs\portfolio.log" 2^>^&1
) > "%ROOT%\logs\_run_portfolio.bat"
start "Portfolio-Service" /min cmd /c "%ROOT%\logs\_run_portfolio.bat"
timeout /t 3 /nobreak > nul

:: 6. Alert Service - Port 8087
echo [ 6/10] Starting Alert Service (port 8087)...
(
    echo @echo off
    echo call "%ROOT%\logs\_load_env.bat"
    echo set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot"
    echo set "PATH=%%JAVA_HOME%%\bin;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Windows\System32;%%PATH%%"
    echo cd /d "%ROOT%\alert-service"
    echo call mvnw.cmd spring-boot:run -DskipTests ^> "%ROOT%\logs\alert.log" 2^>^&1
) > "%ROOT%\logs\_run_alert.bat"
start "Alert-Service" /min cmd /c "%ROOT%\logs\_run_alert.bat"
timeout /t 3 /nobreak > nul

:: 7. Results Service (Java) - Port 8088
echo [ 7/10] Starting Results Service (port 8088)...
(
    echo @echo off
    echo call "%ROOT%\logs\_load_env.bat"
    echo set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot"
    echo set "PATH=%%JAVA_HOME%%\bin;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Windows\System32;%%PATH%%"
    echo cd /d "%ROOT%\results-service"
    echo call mvnw.cmd spring-boot:run -DskipTests ^> "%ROOT%\logs\results.log" 2^>^&1
) > "%ROOT%\logs\_run_results.bat"
start "Results-Service" /min cmd /c "%ROOT%\logs\_run_results.bat"
timeout /t 3 /nobreak > nul

:: 8. Results Service (Python) - Port 8090
echo [ 8/10] Starting Results Python Service (port 8090)...
(
    echo @echo off
    echo call "%ROOT%\logs\_load_env.bat"
    echo cd /d "%ROOT%\results-service-python"
    echo venv\Scripts\python.exe run.py ^> "%ROOT%\logs\results-python.log" 2^>^&1
) > "%ROOT%\logs\_run_results_python.bat"
start "Results-Python" /min cmd /c "%ROOT%\logs\_run_results_python.bat"
timeout /t 3 /nobreak > nul

:: 9. Sheet Import Service - Port 8091
echo [ 9/10] Starting Sheet Import Service (port 8091)...
(
    echo @echo off
    echo call "%ROOT%\logs\_load_env.bat"
    echo set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot"
    echo set "PATH=%%JAVA_HOME%%\bin;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Windows\System32;%%PATH%%"
    echo cd /d "%ROOT%\sheet-import-service"
    echo call mvnw.cmd spring-boot:run -DskipTests ^> "%ROOT%\logs\sheet-import.log" 2^>^&1
) > "%ROOT%\logs\_run_sheet_import.bat"
start "Sheet-Import-Service" /min cmd /c "%ROOT%\logs\_run_sheet_import.bat"
timeout /t 3 /nobreak > nul

:: 10. Announcement Service - Port 8092
echo [10/10] Starting Announcement Service (port 8092)...
(
    echo @echo off
    echo call "%ROOT%\logs\_load_env.bat"
    echo set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot"
    echo set "PATH=%%JAVA_HOME%%\bin;C:\Windows\System32\WindowsPowerShell\v1.0;C:\Windows\System32;%%PATH%%"
    echo cd /d "%ROOT%\announcement-service"
    echo call mvnw.cmd spring-boot:run -DskipTests ^> "%ROOT%\logs\announcement.log" 2^>^&1
) > "%ROOT%\logs\_run_announcement.bat"
start "Announcement-Service" /min cmd /c "%ROOT%\logs\_run_announcement.bat"

echo.
echo ========================================
echo   All services are starting!
echo ========================================
echo.
echo Services:
echo   - Discovery (Eureka) : http://localhost:8761
echo   - OAuth              : http://localhost:8080
echo   - Gateway            : http://localhost:8082
echo   - Reporting          : http://localhost:8083
echo   - Portfolio          : http://localhost:8084
echo   - Alert              : http://localhost:8087
echo   - Results (Java)     : http://localhost:8088
echo   - Results (Python)   : http://localhost:8090
echo   - Sheet Import       : http://localhost:8091
echo   - Announcement       : http://localhost:8092
echo.
echo Logs: logs\ folder
echo Stop: run stop-local.bat
echo.
echo Waiting 60 seconds for services to start...
timeout /t 60 /nobreak > nul

echo.
echo Checking service health...
echo.

netstat -an | findstr "LISTENING" | findstr ":8761 " > nul 2>&1 && (echo   [OK] Discovery    8761) || (echo   [--] Discovery    8761  not ready)
netstat -an | findstr "LISTENING" | findstr ":8080 " > nul 2>&1 && (echo   [OK] OAuth         8080) || (echo   [--] OAuth         8080  not ready)
netstat -an | findstr "LISTENING" | findstr ":8082 " > nul 2>&1 && (echo   [OK] Gateway       8082) || (echo   [--] Gateway       8082  not ready)
netstat -an | findstr "LISTENING" | findstr ":8083 " > nul 2>&1 && (echo   [OK] Reporting     8083) || (echo   [--] Reporting     8083  not ready)
netstat -an | findstr "LISTENING" | findstr ":8084 " > nul 2>&1 && (echo   [OK] Portfolio     8084) || (echo   [--] Portfolio     8084  not ready)
netstat -an | findstr "LISTENING" | findstr ":8087 " > nul 2>&1 && (echo   [OK] Alert         8087) || (echo   [--] Alert         8087  not ready)
netstat -an | findstr "LISTENING" | findstr ":8088 " > nul 2>&1 && (echo   [OK] Results       8088) || (echo   [--] Results       8088  not ready)
netstat -an | findstr "LISTENING" | findstr ":8090 " > nul 2>&1 && (echo   [OK] Results-Py    8090) || (echo   [--] Results-Py    8090  not ready)
netstat -an | findstr "LISTENING" | findstr ":8091 " > nul 2>&1 && (echo   [OK] Sheet-Import  8091) || (echo   [--] Sheet-Import  8091  not ready)
netstat -an | findstr "LISTENING" | findstr ":8092 " > nul 2>&1 && (echo   [OK] Announcement  8092) || (echo   [--] Announcement  8092  not ready)

echo.
echo Open http://localhost:8082 to access the app.
echo.
pause
