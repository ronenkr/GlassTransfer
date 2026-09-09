@echo off
setlocal EnableExtensions EnableDelayedExpansion
rem GlassTransfer sender: serve a folder over HTTP and open the sender page.
rem
rem   sender.bat                        serve the web folder next to this script
rem   sender.bat D:\stuff               serve that folder
rem   sender.bat D:\stuff\backup.zip    serve D:\stuff and fill the file name in
rem   sender.bat D:\stuff 8080          ... on port 8080 instead of 8000

set "ROOT=%~dp0"
set "PORT=8000"
set "SERVE=%ROOT%web"
set "FILE="

if not "%~1"=="" (
  if exist "%~1\" (
    set "SERVE=%~f1"
  ) else if exist "%~1" (
    set "SERVE=%~dp1"
    set "FILE=%~nx1"
  ) else (
    echo No such file or folder: %~1
    exit /b 1
  )
)
if not "%~2"=="" set "PORT=%~2"
if "%SERVE:~-1%"=="\" set "SERVE=%SERVE:~0,-1%"

call :findjdk || exit /b 1

rem The page fetches files from its own folder, so it has to sit in the folder being served.
if not exist "%SERVE%\sender.html" (
  echo Copying sender.html into %SERVE%
  copy /y "%ROOT%web\sender.html" "%SERVE%\" >nul
  if errorlevel 1 (
    echo Could not copy sender.html into %SERVE%
    exit /b 1
  )
)

set "URL=http://127.0.0.1:%PORT%/sender.html"
if defined FILE set "URL=!URL!?file=!FILE: =%%20!"

echo Serving %SERVE%
echo Opening %URL%
echo.
echo   1. Size the browser window: the code fills it, so bigger is faster.
echo   2. Start receiver.bat and drag a rectangle around the code.
echo   3. Click the chunk field at the bottom of the page and leave it focused.
echo.
echo Press Ctrl+C to stop the server.
echo.

rem Full paths: a PATH from another shell must not decide what these two names mean.
start "" /b cmd /c "%SystemRoot%\System32\ping.exe -n 3 127.0.0.1 >nul & start "" "%URL%""
"%JWS%" -b 127.0.0.1 -p %PORT% -d "%SERVE%"
exit /b %errorlevel%

:findjdk
rem GT_JAVA_HOME wins, then the JDK 25 this project was built against, then JAVA_HOME, then PATH.
set "JHOME="
if defined GT_JAVA_HOME if exist "%GT_JAVA_HOME%\bin\jwebserver.exe" set "JHOME=%GT_JAVA_HOME%"
if not defined JHOME if exist "C:\jdk-25.0.4.1+1\bin\jwebserver.exe" set "JHOME=C:\jdk-25.0.4.1+1"
if not defined JHOME if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jwebserver.exe" set "JHOME=%JAVA_HOME%"
if defined JHOME (
  set "JWS=%JHOME%\bin\jwebserver.exe"
  exit /b 0
)
for %%J in (jwebserver.exe) do if not "%%~$PATH:J"=="" set "JWS=%%~$PATH:J"
if defined JWS exit /b 0
echo Could not find jwebserver. Install a JDK 25 ^(winget install --id EclipseAdoptium.Temurin.25.JDK^)
echo or point GT_JAVA_HOME at one, then run this again.
exit /b 1
