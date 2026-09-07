@echo off
REM =====================================================================
REM  Starts the Tomcat that serves the servlet implementation.
REM
REM  ENVIRONMENT NOTE -- why the Java 8 runtime is pinned here
REM  ---------------------------------------------------------
REM  On this machine java.nio.channels.Selector.open() fails under the
REM  Java 25 runtime with
REM
REM      java.io.IOException: Unable to establish loopback connection
REM
REM  every single time. Tomcat builds its NIO connector on a Selector,
REM  so the HTTP connector never starts: port 8080 looks like it is
REM  listening but no request is ever answered. Java 8 uses the older
REM  WindowsSelectorImpl, which works, and Java 8 is in any case the
REM  runtime Tomcat 8.5 was built and tested against.
REM
REM  Set JAVA_HOME yourself to override the pick below.
REM
REM  Usage:  scripts\start_tomcat.bat
REM =====================================================================
setlocal

if not defined CATALINA_HOME set "CATALINA_HOME=C:\xampp\tomcat"

if not defined JAVA_HOME (
  if exist "C:\Program Files\Java\jre-1.8\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Java\jre-1.8"
  ) else if exist "C:\Program Files\Java\jre1.8.0_401\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Java\jre1.8.0_401"
  )
)

if not defined JAVA_HOME (
  echo [tomcat] ERROR: no Java 8 runtime found.
  echo [tomcat] Set JAVA_HOME to a Java 8 JRE and retry.
  exit /b 1
)

set "JAVA=%JAVA_HOME%\bin\java.exe"
echo [tomcat] runtime ..... %JAVA%
echo [tomcat] CATALINA_HOME %CATALINA_HOME%

start "Tomcat - badgeportal" /min "%JAVA%" ^
  -Djava.util.logging.config.file="%CATALINA_HOME%\conf\logging.properties" ^
  -Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager ^
  -Dcatalina.base="%CATALINA_HOME%" ^
  -Dcatalina.home="%CATALINA_HOME%" ^
  -Djava.io.tmpdir="%CATALINA_HOME%\temp" ^
  -Xms256m -Xmx512m ^
  -cp "%CATALINA_HOME%\bin\bootstrap.jar;%CATALINA_HOME%\bin\tomcat-juli.jar" ^
  org.apache.catalina.startup.Bootstrap start

echo.
echo [tomcat] starting -- give it a few seconds, then open
echo          http://localhost:8080/badgeportal/
endlocal
