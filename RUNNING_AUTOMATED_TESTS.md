# Running Automated Tests for BaerenLock

## Overview

BaerenLock now has **fully automated UI tests** for the reward timer functionality. These tests use **UI Automator** to actually interact with the device - launching apps, pressing buttons, and verifying behavior - just like a real user would.

## What Gets Automated

### ✅ Test Case A: Reward Timer Background Operation
**Test:** `RewardTimerBackgroundTest.testRewardTimerDecrementsInBackground()`

**What it does:**
1. Sets Chrome as a reward app
2. Grants 2 minutes reward time
3. Launches Chrome from the launcher
4. Waits 1 minute → Verifies database shows 1 minute remaining
5. Waits another minute → Verifies database shows 0 minutes
6. Verifies Chrome is blocked and doesn't appear in launcher

**Duration:** ~2.5 minutes

### ✅ Test Case B: Foreground-Only Time Tracking
**Test:** `RewardTimerBackgroundTest.testTimerOnlyCountsForegroundTime()`

**What it does:**
1. Sets Chrome as reward app, grants 3 minutes
2. Uses Chrome for 1 minute → Verifies 2 minutes remaining
3. Returns to launcher, waits 2 minutes → Verifies STILL 2 minutes (launcher time not counted)
4. Proves timer only counts time when reward app is in foreground

**Duration:** ~3.5 minutes

## How It Works

### Real Device Interaction
The tests use **UI Automator** which sends real touch events to the device:
```kotlin
// This actually taps the screen where "Chrome" text appears
device.findObject(By.text("Chrome")).click()

// This actually presses the home button
device.pressHome()
```

### Database Monitoring
The tests directly read the **local SQLite database** on the device:
```kotlin
// Reads actual banked_mins value from /data/data/com.talq2me.baerenlock/databases/baeren.db
val mins = getBankedMinsFromDatabase()
```

### Progress Logging
Tests log progress to logcat so you can watch what's happening:
```
Phase 1: Setup - Granting 2 minutes reward time
Phase 2: Launcher - Opening BaerenLock launcher
Phase 3: Chrome launched, package=com.android.chrome
Phase 3: Waiting for first minute to elapse...
  15s elapsed, banked_mins=2
  30s elapsed, banked_mins=2
  45s elapsed, banked_mins=2
  60s elapsed, banked_mins=1  ✅ Timer working!
Phase 4: Waiting for second minute to elapse...
  15s elapsed, banked_mins=1
  ...
  60s elapsed, banked_mins=0  ✅ Time expired!
Phase 5: Chrome successfully blocked
✅ TEST PASSED
```

## Prerequisites

### 1. Chrome Installed on Device/Emulator
The tests use Chrome as the test app (instead of Netflix). Chrome is pre-installed on most emulators.

**If Chrome is not installed:**
- Use a real device (Chrome is usually installed)
- Install Chrome on emulator: `adb install chrome.apk`
- OR modify the test to use a different app (see "Customization" below)

### 2. All Permissions Granted
Before running tests, ensure BaerenLock has:
- ✅ Accessibility Service enabled
- ✅ UsageStats permission granted
- ✅ Display over other apps enabled
- ✅ Battery optimization disabled

**Quick check:**
```bash
adb shell dumpsys accessibility | findstr AppBlockerService
# Should show AppBlockerService as active
```

### 3. Root Access (for database access)
The tests need to read/write the SQLite database, which requires root:
```bash
adb shell su -c "ls /data/data/com.talq2me.baerenlock/databases/"
# Should show baeren.db
```

## Running the Tests

### Option 1: From Android Studio (Recommended)

1. Open `RewardTimerBackgroundTest.kt` in Android Studio
2. Right-click on a test method (or the class)
3. Select "Run 'testRewardTimerDecrementsInBackground()'"
4. Watch the test run on your device/emulator
5. View results in the "Run" panel

**To watch logs in real-time:**
- Open "Logcat" panel
- Filter by tag: `RewardTimerBackgroundTest`
- Watch progress as test runs

### Option 2: From Command Line

Run all tests in the class:
```bash
./gradlew connectedAndroidTest --tests "com.talq2me.baerenlock.RewardTimerBackgroundTest"
```

Run a specific test:
```bash
./gradlew connectedAndroidTest --tests "com.talq2me.baerenlock.RewardTimerBackgroundTest.testRewardTimerDecrementsInBackground"
```

Run only the foreground-time test:
```bash
./gradlew connectedAndroidTest --tests "com.talq2me.baerenlock.RewardTimerBackgroundTest.testTimerOnlyCountsForegroundTime"
```

### Option 3: Watch with Monitoring Script

Open two terminals:

**Terminal 1:** Run the test
```bash
./gradlew connectedAndroidTest --tests "com.talq2me.baerenlock.RewardTimerBackgroundTest.testRewardTimerDecrementsInBackground"
```

**Terminal 2:** Monitor database
```bash
test_reward_timer.bat
# Choose option 1 (Monitor database)
```

This lets you see the database updates in real-time as the test runs.

## Reading Test Results

### Success Output
```
✅ TEST PASSED - All phases completed successfully!
Summary:
  - Initial time: 2 minutes
  - After 1 min: 1 minutes
  - After 2 min: 0 minutes
  - Chrome blocked: true
```

### Failure Output
If the test fails, you'll see which assertion failed:
```
❌ FAILED: Timer should have decremented after 1 minute. Expected 1, got 2

java.lang.AssertionError: Timer should have decremented after 1 minute. Expected 1, got 2
    at RewardTimerBackgroundTest.testRewardTimerDecrementsInBackground:123
```

Common failures and what they mean:
- **"Expected 1, got 2"** → Timer didn't decrement (background timer not working)
- **"Chrome should appear in launcher"** → Launcher not showing reward apps when time > 0
- **"Chrome should NOT appear"** → Launcher still showing reward apps when time = 0
- **"Should be on BaerenLock launcher"** → Launcher not set as default home app

## Customization

### Using a Different App Instead of Chrome

Edit `RewardTimerBackgroundTest.kt`:

```kotlin
// Change these constants at the top of the file:
private val TEST_APP_PACKAGE = "com.android.calculator2"  // Calculator instead of Chrome
private val TEST_APP_LABEL = "Calculator"
```

Common apps on emulators:
- Calculator: `com.android.calculator2` / "Calculator"
- Clock: `com.google.android.deskclock` / "Clock"
- Camera: `com.android.camera2` / "Camera"
- Settings: `com.android.settings` / "Settings"

### Adjusting Test Duration

To test with different durations:

```kotlin
// Change this constant:
private val TEST_DURATION_MINUTES = 5  // Test with 5 minutes instead of 2
```

**Warning:** Longer tests take longer to run!

## Troubleshooting

### Test Hangs or Times Out

**Symptom:** Test runs forever, never completes

**Possible causes:**
1. Chrome not installed → Install Chrome or use different app
2. Permissions not granted → Grant all required permissions
3. Timer not running → Check AppBlockerService is active

**Fix:**
```bash
# Check if Chrome is installed
adb shell pm list packages | grep chrome

# Check if AppBlockerService is running
adb shell dumpsys accessibility | grep AppBlockerService
```

### Database Access Denied

**Symptom:** `Error reading banked_mins from database`

**Cause:** No root access

**Fix:**
```bash
# Check root access
adb shell su -c "whoami"
# Should output: root

# If not root, enable root on emulator or use rooted device
```

### "Chrome should appear in launcher" Fails

**Symptom:** Test fails immediately at launcher phase

**Possible causes:**
1. Launcher not refreshing after granting time
2. Chrome not set as reward app correctly

**Fix:**
- Manually test: Grant time, check if Chrome appears in launcher
- Check logs: Look for "refreshIcons" in logcat
- Verify: Check database shows Chrome in reward_apps

### Timer Not Decrementing

**Symptom:** `banked_mins` stays at 2 after 1 minute

**Possible causes:**
1. UsageStats permission not granted
2. AppBlockerService not running
3. Background timer not initialized

**Fix:**
```bash
# Check logcat for timer events
adb logcat -s RewardManager:D AppBlocker:D | grep performTimerCheck

# Should see:
# "performTimerCheck called"
# "actualUsage=1 min, updating from 2 to 1 minutes"
```

### Test Passes Locally but Fails in CI

**Possible causes:**
1. CI emulator doesn't have Chrome → Use Calculator instead
2. CI needs more time to start apps → Increase sleep times
3. CI emulator is slow → Increase tolerance for timing

**Fix for CI:**
- Use Calculator (always available)
- Add longer sleeps
- Increase assertion tolerance (e.g., allow ±1 minute variance)

## Adding to CI/CD Pipeline

### GitHub Actions Example

```yaml
name: Reward Timer Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v2
      
      - name: Set up JDK
        uses: actions/setup-java@v2
        with:
          java-version: '11'
      
      - name: Run Reward Timer Tests
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 29
          script: ./gradlew connectedAndroidTest --tests "com.talq2me.baerenlock.RewardTimerBackgroundTest"
      
      - name: Upload test results
        uses: actions/upload-artifact@v2
        with:
          name: test-results
          path: app/build/reports/androidTests/
```

## Next Steps

1. **Run the tests** to verify your reward timer implementation works
2. **Add more tests** for edge cases (daily reset, multiple sessions, etc.)
3. **Integrate into CI/CD** to catch regressions automatically
4. **Create tests for other critical flows** (app blocking, whitelisting, etc.)

## Related Files

- `RewardTimerBackgroundTest.kt` - The automated test (this is the main file)
- `TestCases.md` - Full test case documentation
- `test_reward_timer.bat` - Manual testing helper script
- `TESTING_REWARD_TIMER.md` - Manual testing guide
