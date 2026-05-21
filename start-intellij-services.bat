@echo off
echo Starting all services locally (without IntelliJ)...
echo.

:: Set JAVA_HOME if needed
if exist "C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot" (
    set "JAVA_HOME=C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot"
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM Start Discovery Service first (Eureka)
echo Starting Discovery Service (Eureka) on port 8761...
start "Discovery Service" cmd /k "cd /d C:\proj\OauthProj\discovery-service && mvnw.cmd spring-boot:run"
timeout /t 20 /nobreak > nul
echo Waiting for Eureka to start...

REM Start OAuth Service
echo Starting OAuth Service on port 8080...
start "OAuth Service" cmd /k "cd /d C:\proj\OauthProj\oauth && mvnw.cmd spring-boot:run"
timeout /t 5 /nobreak > nul

REM Start Gateway Service
echo Starting Gateway Service on port 8082...
start "Gateway Service" cmd /k "cd /d C:\proj\OauthProj\gateway-service && mvnw.cmd spring-boot:run"
timeout /t 5 /nobreak > nul

REM Start Reporting Service
echo Starting Reporting Service on port 8083...
start "Reporting Service" cmd /k "cd /d C:\proj\OauthProj\reporting-service && mvnw.cmd spring-boot:run"
timeout /t 3 /nobreak > nul

REM Start Portfolio Service
echo Starting Portfolio Service on port 8084...
start "Portfolio Service" cmd /k "cd /d C:\proj\OauthProj\portfolio-service && mvnw.cmd spring-boot:run"
timeout /t 3 /nobreak > nul

REM Start Alert Service
echo Starting Alert Service on port 8087...
start "Alert Service" cmd /k "cd /d C:\proj\OauthProj\alert-service && mvnw.cmd spring-boot:run"
timeout /t 3 /nobreak > nul

REM Start Results Service (Java)
echo Starting Results Service on port 8088...
start "Results Service" cmd /k "cd /d C:\proj\OauthProj\results-service && mvnw.cmd spring-boot:run"
timeout /t 3 /nobreak > nul

REM Start Results Service (Python)
echo Starting Results Python Service on port 8090...
start "Results Python" cmd /k "cd /d C:\proj\OauthProj\results-service-python && venv\Scripts\python.exe run.py"
timeout /t 3 /nobreak > nul

REM Start Sheet Import Service
echo Starting Sheet Import Service on port 8091...
start "Sheet Import Service" cmd /k "cd /d C:\proj\OauthProj\sheet-import-service && mvnw.cmd spring-boot:run"
timeout /t 3 /nobreak > nul

REM Start Announcement Service
echo Starting Announcement Service on port 8092...
start "Announcement Service" cmd /k "cd /d C:\proj\OauthProj\announcement-service && mvnw.cmd spring-boot:run"

echo.
echo All services starting in separate windows!
echo.
echo Service URLs:
echo   Eureka:          http://localhost:8761
echo   OAuth:           http://localhost:8080
echo   Gateway:         http://localhost:8082
echo   Reporting:       http://localhost:8083
echo   Portfolio:       http://localhost:8084
echo   Alert:           http://localhost:8087
echo   Results (Java):  http://localhost:8088
echo   Results (Python):http://localhost:8090
echo   Sheet Import:    http://localhost:8091
echo   Announcement:    http://localhost:8092
echo.
echo Close each command window to stop that service.
echo Or run stop-intellij-services.bat to stop all.
pause
