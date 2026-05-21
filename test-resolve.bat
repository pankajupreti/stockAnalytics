@echo off
cd /d C:\proj\OauthProj\gateway-service
call mvnw.cmd dependency:resolve > C:\proj\OauthProj\resolve-output.txt 2>&1
echo DONE >> C:\proj\OauthProj\resolve-output.txt
