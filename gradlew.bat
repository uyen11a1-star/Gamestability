@echo off
setlocal
set GRADLE_VERSION=8.2
if defined GRADLE_USER_HOME (set BASE=%GRADLE_USER_HOME%\game-stability-gradle) else (set BASE=%USERPROFILE%\.gradle\game-stability-gradle)
set GDIR=%BASE%\gradle-%GRADLE_VERSION%
if exist "%GDIR%\bin\gradle.bat" goto run
where gradle >nul 2>nul
if %errorlevel%==0 (gradle -p "%~dp0" %* & exit /b %errorlevel%)
where powershell >nul 2>nul
if not %errorlevel%==0 (echo Install Gradle %GRADLE_VERSION% or PowerShell to bootstrap it. & exit /b 1)
if not exist "%BASE%" mkdir "%BASE%"
echo Downloading Gradle %GRADLE_VERSION%...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%BASE%\gradle.zip'; Expand-Archive -Force '%BASE%\gradle.zip' '%BASE%'"
if errorlevel 1 exit /b 1
:run
call "%GDIR%\bin\gradle.bat" -p "%~dp0" %*
exit /b %errorlevel%
