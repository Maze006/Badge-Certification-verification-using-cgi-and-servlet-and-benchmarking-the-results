@echo off
REM =====================================================================
REM  Stops Tomcat by sending SHUTDOWN to the port declared in
REM  conf/server.xml (8005 by default), which lets the application shut
REM  down cleanly -- AppListener.contextDestroyed closes the JDBC pool.
REM
REM  Usage:  scripts\stop_tomcat.bat
REM =====================================================================
setlocal

if not defined CATALINA_HOME set "CATALINA_HOME=C:\xampp\tomcat"

if not defined JAVA_HOME (
  if exist "C:\Program Files\Java\jre-1.8\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Java\jre-1.8"
  )
)
if not defined JAVA_HOME (
  echo [tomcat] ERROR: no Java 8 runtime found. Set JAVA_HOME and retry.
  exit /b 1
)

"%JAVA_HOME%\bin\java.exe" ^
  -Dcatalina.base="%CATALINA_HOME%" ^
  -Dcatalina.home="%CATALINA_HOME%" ^
  -cp "%CATALINA_HOME%\bin\bootstrap.jar;%CATALINA_HOME%\bin\tomcat-juli.jar" ^
  org.apache.catalina.startup.Bootstrap stop

echo [tomcat] shutdown signalled.
endlocal
