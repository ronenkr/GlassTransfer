@echo off
setlocal EnableExtensions
rem GlassTransfer receiver: read the code off the screen and write the file to disk.
rem
rem   receiver.bat                       drag a rectangle, receive into .\received
rem   receiver.bat --out-dir D:\in       write somewhere else
rem   receiver.bat --region 20,20,1200,900   skip the drag
rem   receiver.bat --once                exit after one file
rem   receiver.bat --selftest            offline checks, no screen needed
rem
rem Anything you pass is appended to the defaults below, so it overrides them.

set "ROOT=%~dp0"
set "OUT=%ROOT%received"

set "DBLCLICK="
echo %cmdcmdline% | %SystemRoot%\System32\find.exe /i "%~f0" >nul && set "DBLCLICK=1"
if not "%~1"=="" set "DBLCLICK="

call :findjdk || goto :done
if not exist "%OUT%" mkdir "%OUT%"

"%JAVA%" "%ROOT%desktop\GlassReceiver.java" --out-dir "%OUT%" --stats %*
set "RC=%errorlevel%"
if not "%RC%"=="0" echo.& echo Receiver exited with code %RC%.

:done
if defined DBLCLICK pause
exit /b %RC%

:findjdk
rem GT_JAVA_HOME wins, then the JDK 25 this project was built against, then JAVA_HOME, then PATH.
set "JHOME="
if defined GT_JAVA_HOME if exist "%GT_JAVA_HOME%\bin\java.exe" set "JHOME=%GT_JAVA_HOME%"
if not defined JHOME if exist "C:\jdk-25.0.4.1+1\bin\java.exe" set "JHOME=C:\jdk-25.0.4.1+1"
if not defined JHOME if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JHOME=%JAVA_HOME%"
if defined JHOME (
  set "JAVA=%JHOME%\bin\java.exe"
  goto :checkver
)
for %%J in (java.exe) do if not "%%~$PATH:J"=="" set "JAVA=%%~$PATH:J"
if not defined JAVA (
  echo Could not find java. Install a JDK 25 ^(winget install --id EclipseAdoptium.Temurin.25.JDK^)
  echo or point GT_JAVA_HOME at one, then run this again.
  set "RC=1"
  exit /b 1
)

:checkver
set "JV="
for /f "tokens=3" %%v in ('""%JAVA%" -version 2>&1 | findstr /i "version""') do set "JV=%%~v"
for /f "delims=.-+_ " %%m in ("%JV%") do set "JMAJOR=%%m"
if defined JMAJOR if %JMAJOR% LSS 25 (
  echo Found Java %JV% at %JAVA%, but this needs Java 25.
  echo Point GT_JAVA_HOME at a JDK 25 and run this again.
  set "RC=1"
  exit /b 1
)
exit /b 0
