@echo off
echo ========================================
echo   Stopping OAuthProj Microservices
echo ========================================
echo.

docker-compose down

echo.
echo [OK] All services stopped.
echo.
echo To also remove database data, run:
echo   docker-compose down -v
echo.
pause
