@echo off
setlocal
set "ADB=adb"
if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
set "APK=%~dp0..\app\build\outputs\apk\debug\app-debug.apk"
"%ADB%" wait-for-device
"%ADB%" install -r "%APK%"
if errorlevel 1 exit /b %ERRORLEVEL%
"%ADB%" shell am start -W -n com.sweetgirlfriend.pet/com.sweetgirlfriend.pet.app.MainActivity
exit /b %ERRORLEVEL%
