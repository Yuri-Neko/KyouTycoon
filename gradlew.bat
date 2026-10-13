@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0gradlew.ps1" %*
exit /b %ERRORLEVEL%
