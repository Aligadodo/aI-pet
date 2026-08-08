@echo off
setlocal
if not defined ANDROID_SDK_ROOT if defined LOCALAPPDATA set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_HOME set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if not exist "%ANDROID_SDK_ROOT%\emulator\emulator.exe" (
  echo Android emulator not found. Set ANDROID_SDK_ROOT first.
  exit /b 2
)
"%ANDROID_SDK_ROOT%\emulator\emulator.exe" -avd SweetPet_API36 -gpu software -feature -Vulkan -memory 2048 -cores 4 -no-metrics
exit /b %ERRORLEVEL%
