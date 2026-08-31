@echo off
REM ============================================================================
REM build-and-release.bat - Build APK AND auto-upload to GitHub in one command
REM ============================================================================
REM Usage: Just run this file - it builds, then uploads automatically
REM ============================================================================

setlocal enabledelayedexpansion

echo.
echo ============================================================================
echo  PulseStream Build & Release (All-in-One)
echo ============================================================================
echo.

REM ----------------------------------------------------------------------------
REM 1. Build Release APK
REM ----------------------------------------------------------------------------
echo [1/3] Building Release APK...
echo.
call ./gradlew.bat :app:assembleRelease

if %errorlevel% neq 0 (
    echo.
    echo ============================================================================
    echo BUILD FAILED!
    echo ============================================================================
    exit /b 1
)

echo.
echo [BUILD SUCCESS] APK created.

REM ----------------------------------------------------------------------------
REM 2. Find APK and extract version
REM ----------------------------------------------------------------------------
echo.
echo [2/3] Preparing release...

set "APK_DIR=app\build\outputs\apk\release"
for %%f in ("%APK_DIR%\pulsestreamV*.apk") do (
    set "APK_FILE=%%f"
    set "APK_NAME=%%~nxf"
)

if not defined APK_FILE (
    echo ERROR: APK not found after build!
    exit /b 1
)

set "VERSION=%APK_NAME:pulsestreamV=%"
set "VERSION=%VERSION:.apk=%"
echo APK: %APK_NAME%
echo Version: %VERSION%

REM ----------------------------------------------------------------------------
REM 3. Upload to GitHub
REM ----------------------------------------------------------------------------
echo.
echo [3/3] Uploading to GitHub Releases...

REM Delete existing release if any
gh release delete "v%VERSION%" --repo mahdiridoy/APK-Pulse-TV --yes >nul 2>&1

REM Create new release
set "NOTES=## Changes%0A%0A- Version %VERSION%%0A- Built: %DATE% %TIME%"
gh release create "v%VERSION%" "%APK_FILE%" ^
    --repo mahdiridoy/APK-Pulse-TV ^
    --title "PulseStream v%VERSION%" ^
    --notes "%NOTES%" ^
    --latest

if %errorlevel% equ 0 (
    echo.
    echo ============================================================================
    echo SUCCESS! Release published automatically
    echo URL: https://github.com/mahdiridoy/APK-Pulse-TV/releases/tag/v%VERSION%
    echo ============================================================================
) else (
    echo.
    echo ============================================================================
    echo ERROR: Upload failed
    echo ============================================================================
    exit /b 1
)

echo.
pause