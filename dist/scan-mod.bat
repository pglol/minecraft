@echo off
rem Drag your AoT mod .jar (from the instance "mods" folder) onto this file.
if "%~1"=="" (
  echo Drag the mod .jar file onto this .bat file.
  pause
  exit /b
)
java -jar "%~dp0aot-world.jar" scan-mod "%~1" --out "%~dp0titans.txt"
echo.
echo Now open titans.txt next to this file, keep the titans you want, then use add-titans.bat
pause
