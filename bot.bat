@echo off
REM ============================================================================
REM bot.bat - Auto-upload APK to GitHub Releases after local build
REM ============================================================================
REM Usage: Place this in project root, run after ./gradlew assembleRelease
REM ============================================================================

setlocal enabledelayedexpansion

echo.
echo ============================================================================
echo  PulseStream Auto-Release Bot
echo ============================================================================
echo.

REM ----------------------------------------------------------------------------
REM 1. Find the built APK
REM ----------------------------------------------------------------------------
set "APK_DIR=app\build\outputs\apk\release"
set "APK_PATTERN=pulsestreamV*.apk"

echo [1/5] Searching for APK in %APK_DIR%...
if not exist "%APK_DIR%\%APK_PATTERN%" (
    echo ERROR: No APK found! Run './gradlew :app:assembleRelease' first.
    exit /b 1
)

for %%f in ("%APK_DIR%\%APK_PATTERN%") do (
    set "APK_FILE=%%f"
    set "APK_NAME=%%~nxf"
)
echo Found: %APK_NAME%

REM ----------------------------------------------------------------------------
REM 2. Extract version from APK filename (pulsestreamV1.0.0.15.apk -> 1.0.0.15)
REM ----------------------------------------------------------------------------
for /f "tokens=2 delims=V." %%a in ("%APK_NAME%") do set "VERSION=%%a.%%b.%%c.%%d"
REM More robust: use regex-like parsing
set "VERSION=%APK_NAME:pulsestreamV=%"
set "VERSION=%VERSION:.apk=%"
echo Version: %VERSION%

REM ----------------------------------------------------------------------------
REM 3. Read changelog (optional - from git log or prompt)
REM ----------------------------------------------------------------------------
echo.
echo [2/5] Generating changelog...
set "CHANGELOG=## Changes%0A%0A- Built from local machine%0A- Version %VERSION%"
REM Optionally get last 5 commits:
REM for /f "tokens=*" %%i in ('git log --oneline -5') do set "CHANGELOG=!CHANGELOG!%0A%0A- %%i"

REM ----------------------------------------------------------------------------
REM 4. Check if release already exists
REM ----------------------------------------------------------------------------
echo [3/5] Checking existing releases...
gh release view "v%VERSION%" --repo mahdiridoy/APK-Pulse-TV >nul 2>&1
if %errorlevel% equ 0 (
    echo Release v%VERSION% already exists. Deleting...
    gh release delete "v%VERSION%" --repo mahdiridoy/APK-Pulse-TV --yes
    if %errorlevel% neq 0 (
        echo WARNING: Could not delete existing release
    )
)

REM ----------------------------------------------------------------------------
REM 5. Create new release with APK
REM ----------------------------------------------------------------------------
echo [4/5] Creating GitHub release v%VERSION%...
gh release create "v%VERSION%" "%APK_FILE%" ^
    --repo mahdiridoy/APK-Pulse-TV ^
    --title "PulseStream v%VERSION%" ^
    --notes "%CHANGELOG%" ^
    --latest

if %errorlevel% equ 0 (
    echo.
    echo ============================================================================
    echo SUCCESS! Release published: https://github.com/mahdiridoy/APK-Pulse-TV/releases/tag/v%VERSION%
    echo ============================================================================
) else (
    echo.
    echo ============================================================================
    echo ERROR: Failed to create release
    echo ============================================================================
    exit /b 1
)

REM ----------------------------------------------------------------------------
REM 6. Verify release
REM ----------------------------------------------------------------------------
echo [5/5] Verifying release...
gh release view "v%VERSION%" --repo mahdiridoy/APK-Pulse-TV

echo.
echo ============================================================================
echo  Done! Users will receive update notification on next app start.
echo ============================================================================
echo.
pause