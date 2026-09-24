@echo off
rem Drag your world folder (from the instance "saves" folder) onto this file.
rem Without a folder it uses the AttackOnTitan folder next to this file.
set WORLD=%~1
if "%WORLD%"=="" set WORLD=%~dp0AttackOnTitan
java -jar "%~dp0aot-world.jar" titans "%WORLD%" --config "%~dp0titans.txt"
pause
