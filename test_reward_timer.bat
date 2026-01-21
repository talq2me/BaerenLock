@echo off
REM Reward Timer Testing Script (Windows version)
REM This script helps monitor the reward timer in real-time during testing

echo === BaerenLock Reward Timer Test Monitor ===
echo.

REM Check if device is connected
adb devices | findstr "device" > nul
if errorlevel 1 (
    echo X No Android device connected!
    echo Please connect device and enable USB debugging
    pause
    exit /b 1
)

echo √ Device connected
echo.

echo Choose test mode:
echo 1] Monitor database continuously (updates every 5 seconds^)
echo 2] Show current state once
echo 3] Grant reward time (test helper^)
echo 4] Watch logcat for timer events
echo 5] Quick smoke test (2 min with Netflix^)
set /p choice="Enter choice (1-5): "

if "%choice%"=="1" goto monitor
if "%choice%"=="2" goto showstate
if "%choice%"=="3" goto grant
if "%choice%"=="4" goto logcat
if "%choice%"=="5" goto smoketest
goto invalid

:monitor
echo.
echo Monitoring database (Press Ctrl+C to stop^)...
echo.
:monitorloop
cls
call :show_state
timeout /t 5 /nobreak > nul
goto monitorloop

:showstate
call :show_state
pause
goto :eof

:grant
set /p minutes="Enter minutes to grant: "
echo Granting %minutes% minutes...

REM Get active profile
for /f "delims=" %%i in ('adb shell "su -c \"sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db 'SELECT active_profile FROM devices LIMIT 1;'\""') do set profile=%%i

REM Generate timestamp (simplified for Windows)
for /f "tokens=2 delims==" %%i in ('wmic OS Get localdatetime /value') do set datetime=%%i
set timestamp=%datetime:~0,4%-%datetime:~4,2%-%datetime:~6,2%T%datetime:~8,2%:%datetime:~10,2%:%datetime:~12,2%.000-05:00

REM Update database
adb shell "su -c \"sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db 'UPDATE user_data SET banked_mins = %minutes%, banked_mins_timestamp = ''%timestamp%'' WHERE profile = ''%profile%'';'\""

echo √ Granted %minutes% minutes to profile %profile%
echo.
call :show_state
pause
goto :eof

:logcat
echo.
echo Watching logcat for reward timer events (Press Ctrl+C to stop^)...
echo Looking for: timer checks, usage updates, expiration events
echo.
adb logcat -c
adb logcat -s RewardManager:D AppBlocker:D | findstr /i "performTimerCheck actualUsage updating Reward expired background"
goto :eof

:smoketest
echo.
echo === QUICK SMOKE TEST (2 minutes^) ===
echo.
echo This test will:
echo   1. Set Netflix as reward app
echo   2. Grant 2 minutes reward time
echo   3. Monitor for 2 minutes
echo   4. Verify time expires and Netflix is blocked
echo.
set /p confirm="Press Enter to start test (or Ctrl+C to cancel^)..."

REM Get active profile
for /f "delims=" %%i in ('adb shell "su -c \"sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db 'SELECT active_profile FROM devices LIMIT 1;'\""') do set profile=%%i

echo.
echo Setting up test for profile: %profile%
echo.

REM Add Netflix as reward app
adb shell "su -c \"sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db 'UPDATE user_data SET reward_apps = ''com.netflix.mediaclient'' WHERE profile = ''%profile%'';'\""
echo √ Set Netflix as reward app

REM Grant 2 minutes
for /f "tokens=2 delims==" %%i in ('wmic OS Get localdatetime /value') do set datetime=%%i
set timestamp=%datetime:~0,4%-%datetime:~4,2%-%datetime:~6,2%T%datetime:~8,2%:%datetime:~10,2%:%datetime:~12,2%.000-05:00
adb shell "su -c \"sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db 'UPDATE user_data SET banked_mins = 2, banked_mins_timestamp = ''%timestamp%'' WHERE profile = ''%profile%'';'\""
echo √ Granted 2 minutes reward time

echo.
echo ================================================
echo TEST INSTRUCTIONS:
echo ================================================
echo 1. Open BaerenLock on device
echo 2. Verify Netflix appears in launcher
echo 3. Launch Netflix
echo 4. Keep Netflix in foreground
echo.
echo Expected results:
echo   - After 1 min: banked_mins = 1
echo   - After 2 min: banked_mins = 0, Netflix blocked
echo.
set /p confirm="Press Enter to start monitoring..."

echo.
echo MONITORING - Keep Netflix in foreground!
echo.

set start_time=%time%
set last_mins=2
set elapsed=0

:smokeloop
cls
echo === Smoke Test - Elapsed: %elapsed% seconds ===
echo.

REM Get current banked_mins
for /f "delims=" %%i in ('adb shell "su -c \"sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db 'SELECT banked_mins FROM user_data WHERE profile = ''%profile%'';'\""') do set current_mins=%%i

echo Banked Mins: %current_mins% / 2
echo.

REM Check if time expired
if "%current_mins%"=="0" (
    echo.
    echo ========================================
    echo √√√ REWARD TIME EXPIRED! √√√
    echo ========================================
    echo.
    echo Expected: Netflix should be blocked now
    echo Verify: Try to launch Netflix - it should be blocked immediately
    echo.
    call :show_state
    echo.
    echo Test complete!
    pause
    goto :eof
)

REM Show full state
adb shell "su -c \"sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db 'SELECT profile, banked_mins, datetime(banked_mins_timestamp, ''localtime'') FROM user_data WHERE profile = ''%profile%'';'\""

echo.
echo Press Ctrl+C to stop monitoring
timeout /t 5 /nobreak > nul

set /a elapsed+=5
goto smokeloop

:invalid
echo Invalid choice
pause
goto :eof

REM Helper function to show current state
:show_state
echo === Current State ===
echo.
echo Reward Time:
adb shell "su -c \"sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db 'SELECT profile, banked_mins, datetime(banked_mins_timestamp, ''localtime'') FROM user_data ORDER BY profile;'\""
echo.
echo Active Profile:
adb shell "su -c \"sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db 'SELECT active_profile FROM devices LIMIT 1;'\""
echo.
echo Reward Apps:
adb shell "su -c \"sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db 'SELECT profile, reward_apps FROM user_data ORDER BY profile;'\""
echo.
echo ================================================
goto :eof
