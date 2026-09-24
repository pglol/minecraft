@echo off
rem Generates the full Attack on Titan map into a folder called "AttackOnTitan" next to this file.
rem Needs Java 17 or newer on PATH (https://adoptium.net).
java -Xmx4G -jar "%~dp0aot-world.jar" generate "%~dp0AttackOnTitan" %*
echo.
echo Finished. Copy the AttackOnTitan folder into your instance's "saves" folder.
pause
