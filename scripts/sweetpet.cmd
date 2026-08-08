@echo off
setlocal

set "PYTHONUTF8=1"
set "SCRIPT_PATH=%~dp0sweetpet.py"
set "REPO_VENV=%~dp0..\.venv\Scripts\python.exe"

if defined SWEETPET_PYTHON if exist "%SWEETPET_PYTHON%" (
    "%SWEETPET_PYTHON%" "%SCRIPT_PATH%" %*
    exit /b %ERRORLEVEL%
)

if exist "%REPO_VENV%" (
    "%REPO_VENV%" "%SCRIPT_PATH%" %*
    exit /b %ERRORLEVEL%
)

where python >nul 2>nul
if not errorlevel 1 (
    python "%SCRIPT_PATH%" %*
    exit /b %ERRORLEVEL%
)

where py >nul 2>nul
if not errorlevel 1 (
    py -3.11 "%SCRIPT_PATH%" %*
    exit /b %ERRORLEVEL%
)

>&2 echo Python 3.11+ was not found. Set SWEETPET_PYTHON or install Python.
exit /b 2
