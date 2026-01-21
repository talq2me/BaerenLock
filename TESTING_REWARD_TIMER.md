# Reward Timer Testing Guide

## Quick Start - 5 Minute Smoke Test

This is the fastest way to verify the reward timer is working correctly.

### Windows:
```batch
test_reward_timer.bat
# Choose option 5 (Quick smoke test)
```

### Linux/Mac:
```bash
bash test_reward_timer.sh
# Choose option 5 (Full test - Grant time and monitor)
```

**What it does:**
1. Sets Netflix as a reward app
2. Grants 2 minutes reward time
3. Monitors database every 5 seconds
4. Verifies time decrements correctly
5. Confirms Netflix is blocked when time expires

**Expected output:**
```
Banked Mins: 2 / 2  (start)
... wait 1 minute ...
Banked Mins: 1 / 2  ✅ Timer working!
... wait 1 minute ...
Banked Mins: 0 / 2  ✅ Time expired!
Netflix should be blocked now
```

---

## Manual Testing (Full Test Case A)

This is the comprehensive test for background timer functionality.

### Prerequisites:
1. Device connected via USB
2. USB debugging enabled
3. Root access (for database access)
4. All permissions granted in BaerenLock:
   - ✅ Accessibility Service
   - ✅ UsageStats permission
   - ✅ Display over other apps
   - ✅ Battery optimization disabled

### Test Steps:

1. **Open monitoring terminal:**
   ```batch
   # Windows
   test_reward_timer.bat
   # Choose option 1 (Monitor database continuously)
   ```

2. **In a second terminal, watch logcat:**
   ```batch
   test_reward_timer.bat
   # Choose option 4 (Watch logcat)
   ```

3. **On the device:**
   - Open BaerenLock launcher
   - Settings > Add Reward Time > 5 minutes
   - Verify Netflix appears in launcher
   - Launch Netflix
   - **Keep Netflix in foreground for full 5 minutes**

4. **Monitor results:**
   - Watch terminal #1: `banked_mins` should decrease by 1 every minute
   - Watch terminal #2: Look for "actualUsage" log entries
   - After 5 minutes: Netflix should be blocked, launcher appears

### Expected Timeline:
```
00:00 - Grant 5 min ➜ banked_mins = 5
01:00 - ✅ Update  ➜ banked_mins = 4
02:00 - ✅ Update  ➜ banked_mins = 3
03:00 - ✅ Update  ➜ banked_mins = 2
04:00 - ✅ Update  ➜ banked_mins = 1
05:00 - ✅ Expire  ➜ banked_mins = 0, Netflix blocked
```

---

## Test Results Checklist

Use this checklist to verify the timer is working correctly:

### ✅ Basic Functionality
- [ ] Timer starts when reward time is granted
- [ ] Reward app appears in launcher when time > 0
- [ ] Reward app can be launched when time > 0
- [ ] Database `banked_mins` decreases every minute
- [ ] Timer stops when `banked_mins` reaches 0
- [ ] Reward app is blocked when time expires
- [ ] Reward app disappears from launcher when time expires

### ✅ Background Operation (CRITICAL)
- [ ] Timer continues running when BaerenLock is in background
- [ ] Timer continues running when reward app is in foreground
- [ ] Database updates every minute while reward app is active
- [ ] No manual intervention required (fully automatic)

### ✅ Foreground-Only Tracking
- [ ] Time decrements when reward app is in foreground
- [ ] Time does NOT decrement when on BaerenLock launcher
- [ ] Time does NOT decrement when other apps are foreground
- [ ] Only counts actual usage time, not total elapsed time

### ✅ Cloud Sync
- [ ] Database updates sync to cloud within 2 minutes
- [ ] Cloud `banked_mins` matches local database
- [ ] Cloud `banked_mins_timestamp` updates correctly

### ✅ Edge Cases
- [ ] Timer persists across BaerenLock app restarts
- [ ] Timer persists across device reboots (if background service restarts)
- [ ] Multiple reward sessions in same day work correctly
- [ ] Timer resets at midnight (daily reset)

---

## Troubleshooting

### Timer not decrementing:

1. **Check UsageStats permission:**
   ```batch
   adb shell dumpsys usagestats | findstr baerenlock
   ```
   If empty, grant permission: Settings > Apps > BaerenLock > Permissions > Usage access

2. **Check AppBlockerService is running:**
   ```batch
   adb shell dumpsys accessibility | findstr AppBlockerService
   ```
   Should show "AppBlockerService" as active

3. **Check for errors in logcat:**
   ```batch
   adb logcat -s RewardManager:E AppBlocker:E
   ```
   Look for error messages

### Database not updating:

1. **Verify database is writable:**
   ```batch
   adb shell "su -c 'ls -la /data/data/com.talq2me.baerenlock/databases/'"
   ```

2. **Check for database locks:**
   ```batch
   adb shell "su -c 'lsof | grep baeren.db'"
   ```

### Reward app not blocked:

1. **Check reward app is configured:**
   ```batch
   adb shell "su -c \"sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db 'SELECT reward_apps FROM user_data;'\""
   ```

2. **Verify accessibility service is blocking:**
   ```batch
   adb logcat -s AppBlocker:D | grep "BLOCKING"
   ```

---

## Automated Testing

For CI/CD or regression testing:

### Option 1: Use the test script
```batch
# Automated smoke test (returns exit code)
test_reward_timer.bat 5  # Runs quick smoke test automatically
```

### Option 2: Direct database monitoring
```batch
# Grant 2 minutes and verify it decrements
adb shell "su -c \"sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db 'UPDATE user_data SET banked_mins = 2;'\""

# Wait 60 seconds
timeout /t 60 /nobreak

# Check if it decreased to 1
adb shell "su -c \"sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db 'SELECT banked_mins FROM user_data;'\""
# Expected: 1
```

### Option 3: UI Automator tests
```kotlin
// See TestCases.md for automation opportunities
// Tests A, B, and Quick Smoke Test are good candidates
```

---

## Success Criteria

**The reward timer is working correctly if:**

1. ✅ Database updates every 60 seconds (±10 sec tolerance) while reward app is foreground
2. ✅ Time expires at exactly the granted duration (±30 sec tolerance)
3. ✅ Reward app is blocked immediately when time reaches 0
4. ✅ No crashes or errors in logcat
5. ✅ Works without BaerenLock in foreground (background operation)
6. ✅ Cloud database syncs within 2 minutes

**If any of these fail, the timer is NOT working correctly.**

---

## Next Steps

After manual testing is successful:

1. **Document results** in TestCases.md
2. **Automate critical tests** (Test Case A, B, Quick Smoke Test)
3. **Add to CI/CD pipeline** for regression testing
4. **Test on multiple devices** (different Android versions)
5. **Test with different reward apps** (not just Netflix)

---

## Files

- `TestCases.md` - Comprehensive test case documentation
- `test_reward_timer.bat` - Windows testing script
- `test_reward_timer.sh` - Linux/Mac testing script
- `TESTING_REWARD_TIMER.md` - This file
