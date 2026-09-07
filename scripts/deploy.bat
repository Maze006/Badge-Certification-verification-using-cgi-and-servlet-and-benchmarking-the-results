@echo off
REM =====================================================================
REM  Deploys BOTH implementations:
REM
REM    servlet  ->  %CATALINA_HOME%\webapps\badgeportal\
REM    cgi      ->  %APACHE_CGI_BIN%\   (C:\xampp\cgi-bin by default)
REM
REM  Also rewrites the shebang line of verify.py to whichever python.exe
REM  is actually installed, using the 8.3 short path so that a space in
REM  "Program Files" cannot break Apache CGI interpreter resolution.
REM
REM  Usage:  scripts\deploy.bat
REM =====================================================================
setlocal enabledelayedexpansion

set "PROJECT=%~dp0.."
pushd "%PROJECT%"

if not defined CATALINA_HOME   set "CATALINA_HOME=C:\xampp\tomcat"
if not defined APACHE_CGI_BIN  set "APACHE_CGI_BIN=C:\xampp\cgi-bin"

REM --- build first ------------------------------------------------------
call "%~dp0build.bat"
if errorlevel 1 (
  echo [deploy] aborted -- build failed
  popd & exit /b 1
)

REM --- servlet ----------------------------------------------------------
set "WEBAPP=%CATALINA_HOME%\webapps\badgeportal"
echo.
echo [deploy] servlet -^> %WEBAPP%
if exist "%WEBAPP%" rmdir /s /q "%WEBAPP%"
mkdir "%WEBAPP%"
xcopy /e /i /y /q "build\badgeportal\*" "%WEBAPP%\" >nul

REM --- cgi --------------------------------------------------------------
echo [deploy] cgi ....-^> %APACHE_CGI_BIN%
if not exist "%APACHE_CGI_BIN%" mkdir "%APACHE_CGI_BIN%"
copy /y "cgi\badgecode.py"              "%APACHE_CGI_BIN%\" >nul
copy /y "cgi\dbconfig.py"               "%APACHE_CGI_BIN%\" >nul
copy /y "config\badgeportal.properties" "%APACHE_CGI_BIN%\" >nul

REM Vendor the MySQL driver next to the script. See the comment at the
REM top of cgi/verify.py: Apache does not pass APPDATA to CGI processes,
REM so a user-scoped pip install is invisible to them. Done once -- the
REM folder is skipped on later deploys.
if exist "%APACHE_CGI_BIN%\pylib\mysql" (
  echo [deploy] cgi driver .. already vendored, skipping
) else (
  echo [deploy] cgi driver .. installing mysql-connector-python into pylib\
  python -m pip install --quiet --upgrade --target "%APACHE_CGI_BIN%\pylib" mysql-connector-python
  if errorlevel 1 echo [deploy] WARNING: could not vendor the MySQL driver.
)

REM Copy verify.py across, rewriting its shebang to the python.exe that
REM is actually installed (as a space-free 8.3 path -- see the helper).
python "scripts\fix_shebang.py" "cgi\verify.py" "%APACHE_CGI_BIN%"
if errorlevel 1 (
  echo [deploy] WARNING: could not rewrite the verify.py shebang.
  echo [deploy] Copying it unchanged -- check its first line by hand.
  copy /y "cgi\verify.py" "%APACHE_CGI_BIN%\" >nul
)

echo.
echo [deploy] done.
echo.
echo   Servlet   http://localhost:8080/badgeportal/
echo   CGI       http://localhost/cgi-bin/verify.py?code=SF-XXXX-XXXX-XXXX
echo.
echo   Start Tomcat  : C:\xampp\catalina_start.bat
echo   Start Apache  : C:\xampp\apache_start.bat
popd
endlocal
