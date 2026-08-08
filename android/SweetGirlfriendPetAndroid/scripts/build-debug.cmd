@echo off
setlocal
if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if not defined ANDROID_SDK_ROOT if defined LOCALAPPDATA set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_HOME set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
pushd "%~dp0.."
call gradlew.bat clean test assembleDebug --no-daemon
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%
