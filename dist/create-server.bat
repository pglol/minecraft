@echo off
setlocal
rem Builds a ready-to-run Attack on Titan RPG server next to this file (folder "AoT-Server").
rem You will be asked for your modpack's mods folder and the generated world folder:
rem drag each folder into this window and press Enter.
echo.
echo  === Attack on Titan RPG - server creator ===
echo.
echo  Modpack mods folder. In the Modrinth app: right-click the profile ^> Open folder ^> mods
echo  (usually %%APPDATA%%\ModrinthApp\profiles\^<profile^>\mods)
set /p MODS=Drag the mods folder here and press Enter: 
set MODS=%MODS:"=%
echo.
set WORLD=%~dp0AttackOnTitan
set /p WORLD2=Drag the world folder here (Enter = %WORLD%): 
if not "%WORLD2%"=="" set WORLD=%WORLD2:"=%
echo.
set RAM=6G
set /p RAM2=Server memory (Enter = 6G): 
if not "%RAM2%"=="" set RAM=%RAM2%
echo.
echo  The server needs you to accept the Minecraft EULA: https://aka.ms/MinecraftEULA
set /p EULA=Do you accept it? (y/n): 
set EULAFLAG=
if /i "%EULA%"=="y" set EULAFLAG=--accept-eula
java -jar "%~dp0aot-world.jar" server "%~dp0AoT-Server" --mods "%MODS%" --world "%WORLD%" --ram %RAM% %EULAFLAG%
echo.
pause
