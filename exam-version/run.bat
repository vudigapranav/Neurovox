@echo off
cd /d "%~dp0"
javac Main.java audio\*.java analysis\*.java
if errorlevel 1 pause & exit /b
java Main
pause
