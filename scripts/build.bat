@echo off
REM =====================================================================
REM  Compiles the servlet sources and assembles a deployable web app in
REM  build\badgeportal\  (an exploded WAR -- same folder layout a .war
REM  would unpack to, which is what Tomcat serves from webapps\).
REM
REM  Usage:  scripts\build.bat
REM =====================================================================
setlocal enabledelayedexpansion

set "PROJECT=%~dp0.."
pushd "%PROJECT%"

REM --- locate the JDK ---------------------------------------------------
if not defined JAVA_HOME (
  if exist "C:\Program Files\Java\jdk-25.0.2\bin\javac.exe" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25.0.2"
  )
)
if defined JAVA_HOME (
  set "JAVAC=%JAVA_HOME%\bin\javac.exe"
) else (
  set "JAVAC=javac"
)

REM --- locate Tomcat, for servlet-api.jar --------------------------------
if not defined CATALINA_HOME set "CATALINA_HOME=C:\xampp\tomcat"
set "SERVLET_API=%CATALINA_HOME%\lib\servlet-api.jar"

if not exist "%SERVLET_API%" (
  echo [build] ERROR: servlet-api.jar not found at "%SERVLET_API%"
  echo [build] Set CATALINA_HOME to your Tomcat installation and retry.
  popd & exit /b 1
)

set "OUT=build\badgeportal"

echo [build] JDK ........ %JAVAC%
echo [build] Servlet API . %SERVLET_API%
echo [build] Output ...... %PROJECT%\%OUT%
echo.

REM --- clean ------------------------------------------------------------
if exist "build" rmdir /s /q "build"
mkdir "%OUT%\WEB-INF\classes"
mkdir "%OUT%\WEB-INF\lib"

REM --- compile ----------------------------------------------------------
REM Build a javac argfile. Two Windows-specific quirks to work around:
REM   * this project lives under a path containing spaces, so each entry
REM     has to be quoted or javac splits it on whitespace;
REM   * inside those quotes javac treats a backslash as an escape
REM     character, so the separators are flipped to forward slashes
REM     (which javac accepts on Windows perfectly well).
> "build\sources.txt" (
  for /f "delims=" %%F in ('dir /b /s "servlet\src\*.java"') do (
    set "SRC=%%F"
    echo "!SRC:\=/!"
  )
)

REM --release 8 because Tomcat 8.5 is run on the Java 8 runtime -- see
REM scripts\start_tomcat.bat and the "Environment note" in README.md.
REM Compiling with a newer JDK is fine; targeting a newer class file
REM version is not, since Java 8 would refuse to load it.
echo [build] compiling (targeting Java 8)...
"%JAVAC%" -encoding UTF-8 -Xlint:-options ^
  --release 8 ^
  -cp "%SERVLET_API%" ^
  -d "%OUT%\WEB-INF\classes" ^
  @"build\sources.txt"

if errorlevel 1 (
  echo.
  echo [build] COMPILATION FAILED
  popd & exit /b 1
)

REM --- assemble ---------------------------------------------------------
echo [build] assembling web app...
xcopy /e /i /y /q "servlet\web\*" "%OUT%\" >nul
copy /y "lib\mysql-connector-j-8.4.0.jar" "%OUT%\WEB-INF\lib\" >nul
copy /y "config\badgeportal.properties"   "%OUT%\WEB-INF\" >nul

echo.
echo [build] OK -- exploded web app ready at %OUT%
echo [build] next: scripts\deploy.bat
popd
endlocal
