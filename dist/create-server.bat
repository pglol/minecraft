@echo off
rem Builds a ready-to-run Attack on Titan RPG server in the "AoT-Server" folder next to this file.
rem Just double-click it and answer the questions (you can drag folders into the window).
title AoT RPG - server creator
where java >nul 2>nul
if errorlevel 1 (
  echo Java was not found. Install Java 21 from https://adoptium.net and try again.
  pause
  exit /b
)
java -jar "%~dp0aot-world.jar" server "%~dp0AoT-Server"
echo.
pause
