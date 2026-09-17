@echo off
set DIRNAME=%~dp0
set APP_HOME=%DIRNAME%
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if not exist "%CLASSPATH%" (
  echo gradle-wrapper.jar missing. Open in Android Studio or run: gradle wrapper --gradle-version 8.7
  exit /b 1
)
java %DEFAULT_JVM_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
