@echo off
echo ========================================
echo   Starting OAuthProj Microservices
echo ========================================
echo.

:: Check if Docker is running
docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not running. Please start Docker Desktop first.
    pause
    exit /b 1
)

echo [OK] Docker is running
echo.

:: Build and start all services
echo Building and starting all services...
echo This may take a few minutes on first run.
echo.

docker-compose up --build -d

if errorlevel 1 (
    echo.
    echo [ERROR] Failed to start services. Check the logs above.
    pause
    exit /b 1
)

echo.
echo ========================================
echo   All services are starting!
echo ========================================
echo.
echo Services will be available at:
echo   - Gateway:      http://localhost:8082
echo   - Eureka:       http://localhost:8761
echo   - OAuth:        http://localhost:8080
echo   - Portfolio:    http://localhost:8084
echo   - Announcement: http://localhost:8085
echo   - Sheet-Import: http://localhost:8086
echo   - Alert:        http://localhost:8087
echo   - Results:      http://localhost:8088
echo   - Reporting:    http://localhost:8083
echo.
echo Useful commands:
echo   docker-compose logs -f          View all logs
echo   docker-compose logs -f gateway  View gateway logs
echo   docker-compose ps               Check status
echo   docker-compose down             Stop all
echo.
echo Waiting for services to become healthy...
timeout /t 10 /nobreak >nul

:: Show status
docker-compose ps

echo.
pause
