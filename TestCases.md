- switch profiles to the other profile. new profile in devices.active_profile and in UI of baerenLock.
- switch profiles from baerenEd. new profile in devices.active_profiles, and UI of baerenEd, and upon returning to baerenLock, profile has been updated to active_profile.
- launching settings prompts for pin, entering pin 1234 shows settings menu.
- ✅ **AUTOMATED** - change settings for email and changes are reflected in settings table of db. (Test: `SettingsDatabaseIntegrationTest`)
- upon fresh install, accessibility services will not be set, devices table should show unhealthy state and note accessibility as the problem. turning on accessibility for BaerenLock and returning to baerenlock will set the state in the db to healthy and remove the problem.
- grant 5 min reward time in baerenlock, 5 min pushed to db user_data table for active profile. also reflected in baerenEd. the reward apps are automatically unlocked and time starts counting down once a reward app is in foreground. check db is counting down every 1 min. after 5 mins, the reward app is blocked and user cannot go back to it successfully using recent apps, it is blocked every time.
- repeat above granting the time in baerened and ensure after reward time is up, baerenEd does not report any reward time left. (possibly remove the use reward time button).

- ✅ **AUTOMATED** - set a whitelist app and verify it shows in the launcher. verify it is saved to the database user_data table (Test: `WhitelistAppDatabaseIntegrationTest.testAddWhitelistAppSavedToDatabase`)
- ✅ **AUTOMATED** - remove a whitelist app and verify it is removed from the launcher. verify it is saved to the user_data table (Test: `WhitelistAppDatabaseIntegrationTest.testRemoveWhitelistAppRemovedFromDatabase`)
- ✅ **AUTOMATED** - set a blacklisted app and verify it is saved to the user_data table. try to launch this app and verify it is blocked. (Test: `BlacklistAppDatabaseIntegrationTest` - database verification automated, app blocking verification still manual)
- ⚠️ **PARTIALLY AUTOMATED** - set a reward app and verify it is saved to the user_data table. grant 1 min time and verify the app shows up in the launcher, run the app in the foreground for 1 min until the time expires and verify it is now blocked and doesn't appear in the launcher. (Test: `RewardAppDatabaseIntegrationTest` - database verification automated, launcher/blocking verification still manual)

## Critical Reward Timer Tests (Background Operation)

### Test Case A: Reward Timer Decrements While App is in Background
**✅ AUTOMATED** - See `RewardTimerBackgroundTest.testRewardTimerDecrementsInBackground()`
**Priority:** CRITICAL - Core functionality
**Prerequisites:** 
- All permissions granted (Accessibility, UsageStats, Overlay, Battery Optimization disabled)
- Netflix set as a reward app
- Database accessible for monitoring

**Steps:**
1. Open BaerenLock launcher
2. Grant 5 minutes reward time (Settings > Add Reward Time > 5)
3. Verify Netflix appears in launcher
4. Launch Netflix from launcher
5. Use Netflix (keep it in foreground for full test duration)
6. While Netflix is running, monitor the database `user_data.banked_mins` field every 30 seconds
7. After 5 minutes total, try to continue using Netflix

**Expected Results:**
- Initial: `banked_mins = 5` in database
- After 1 min: `banked_mins = 4` (verify via database query)
- After 2 min: `banked_mins = 3`
- After 3 min: `banked_mins = 2`
- After 4 min: `banked_mins = 1`
- After 5 min: `banked_mins = 0`
- After 5 min: BaerenLock launcher appears (Netflix is killed/blocked)
- After 5 min: Attempting to launch Netflix fails (blocked immediately)

**How to Monitor Database:**
```bash
# Connect to device
adb shell
# Query database every 30 seconds
watch -n 30 'su -c "sqlite3 /data/data/com.talq2me.baerenlock/databases/baeren.db \"SELECT profile, banked_mins, banked_mins_timestamp FROM user_data WHERE profile='AM';\""'
```

**Logcat Monitoring:**
```bash
adb logcat -s RewardManager AppBlocker -v time | grep -E "actualUsage|updating from|Reward time expired"
```

**Pass Criteria:**
- Database updates every minute (±10 seconds tolerance)
- Timer decrements only while reward app is in foreground
- Time expires exactly at 5 minutes (±30 seconds tolerance)
- Reward app is blocked immediately when time expires
- No manual intervention required (fully automatic)

---

### Test Case B: Timer Only Counts Foreground Time (Not Launcher Time)
**✅ AUTOMATED** - See `RewardTimerBackgroundTest.testTimerOnlyCountsForegroundTime()`
**Priority:** CRITICAL - Prevents time theft
**Prerequisites:** Same as Test Case A

**Steps:**
1. Grant 3 minutes reward time
2. Launch Netflix, use for 1 minute
3. Press HOME button to return to BaerenLock launcher
4. Stay on launcher for 2 minutes (don't use Netflix)
5. Check database `banked_mins`
6. Launch Netflix again
7. Use Netflix for 2 more minutes
8. Check database again

**Expected Results:**
- After step 2 (1 min Netflix): `banked_mins = 2`
- After step 4 (2 min on launcher): `banked_mins = 2` (NO CHANGE - launcher time not counted)
- After step 7 (2 more min Netflix): `banked_mins = 0`
- Netflix should be blocked after exactly 3 minutes of Netflix usage (not 5 minutes total elapsed)

**Pass Criteria:**
- Time on launcher is NOT counted toward reward usage
- Only time with reward app in foreground is counted
- Total Netflix usage time is exactly 3 minutes, regardless of how long spent on launcher

---

### Test Case C: Timer Persists Across BaerenLock Restarts
**Priority:** HIGH - Reliability
**Prerequisites:** Same as Test Case A

**Steps:**
1. Grant 5 minutes reward time
2. Verify `banked_mins = 5` in database
3. Launch Netflix, use for 2 minutes
4. While Netflix is running, force-kill BaerenLock: `adb shell am force-stop com.talq2me.baerenlock`
5. Wait 10 seconds for BaerenLock to restart (AccessibilityService will auto-restart)
6. Check database immediately after restart
7. Continue using Netflix for 3 more minutes
8. Verify Netflix is blocked

**Expected Results:**
- After step 3: `banked_mins = 3` (approximately, ±1 min tolerance)
- After step 6: `banked_mins = 3` (persisted through restart)
- After step 7: `banked_mins = 0` and Netflix blocked
- Total Netflix usage time = 5 minutes (restart didn't reset the timer)

**Pass Criteria:**
- Timer state persists across app crashes/kills
- Timer resumes accurately after restart
- No time is lost or gained during restart

---

### Test Case D: Timer Updates Cloud Database
**Priority:** HIGH - Cross-device sync
**Prerequisites:** 
- Same as Test Case A
- Network connectivity enabled
- Cloud sync enabled

**Steps:**
1. Grant 5 minutes reward time
2. Verify cloud database shows `banked_mins = 5` for active profile
3. Launch Netflix, use for 2 minutes
4. Check cloud database
5. Use Netflix for 3 more minutes until blocked
6. Check cloud database

**Expected Results:**
- After step 2: Cloud `user_data.banked_mins = 5`
- After step 4: Cloud `user_data.banked_mins = 3` (±1 min, may have sync delay)
- After step 6: Cloud `user_data.banked_mins = 0`
- Cloud `banked_mins_timestamp` updates with each change

**How to Check Cloud:**
```sql
-- Run in Supabase SQL Editor
SELECT profile, banked_mins, banked_mins_timestamp 
FROM user_data 
WHERE device_id = 'ba7b175e8723fa01' AND profile = 'AM';
```

**Pass Criteria:**
- Cloud database updates within 2 minutes of local changes
- Final state (0 minutes) is reflected in cloud
- Timestamp is accurate (EST timezone)

---

### Test Case E: Multiple Sessions in One Day
**Priority:** MEDIUM - Edge case handling
**Prerequisites:** Same as Test Case A

**Steps:**
1. Grant 2 minutes reward time
2. Use Netflix for 2 minutes until blocked
3. Verify `banked_mins = 0`
4. Grant another 3 minutes reward time (same day)
5. Use Netflix for 3 minutes until blocked
6. Verify `banked_mins = 0`

**Expected Results:**
- First session: 2 minutes works correctly, time expires
- After granting more time: Netflix appears in launcher again
- Second session: 3 minutes works correctly, time expires
- Both sessions are tracked independently
- No time from first session carries over

**Pass Criteria:**
- Multiple reward sessions can be granted in same day
- Each session is tracked accurately
- Previous session doesn't interfere with new session

---

### Test Case F: Timer Behavior at Midnight (Daily Reset)
**Priority:** MEDIUM - Daily reset logic
**Prerequisites:** Same as Test Case A

**Steps:**
1. 11:58 PM: Grant 5 minutes reward time
2. 11:59 PM: Launch Netflix, use for 2 minutes (crosses midnight)
3. 12:01 AM: Check database
4. Continue using Netflix for 3 more minutes
5. Verify Netflix blocking behavior

**Expected Results:**
- At midnight: Timer should reset `banked_mins = 0` (daily reset)
- Netflix should be blocked immediately at midnight (or within 30 seconds)
- Remaining time from previous day is lost

**Pass Criteria:**
- Daily reset happens at midnight
- Reward time doesn't carry over to next day
- Netflix is blocked when reset occurs

---

## Quick Smoke Test (5 minutes)
**For rapid verification after code changes:**

1. ✅ Grant 2 min reward time
2. ✅ Netflix appears in launcher
3. ✅ Launch Netflix
4. ✅ Wait 1 minute → Check DB: `banked_mins = 1` ✓
5. ✅ Wait 1 more minute → Netflix blocked ✓
6. ✅ Try to launch Netflix → Immediately blocked ✓
7. ✅ Check DB: `banked_mins = 0` ✓

**If all steps pass: Core functionality is working**

---

## Automation Opportunities

**High Priority for Automation:**
1. Test Case A - Can be automated with UI Automator + database monitoring
2. Test Case B - Can be automated (launch app, home button, measure time)
3. Quick Smoke Test - Should be in CI/CD pipeline

**Medium Priority:**
4. Test Case C - Requires app restart, automatable with adb commands
5. Test Case D - Requires network/cloud setup but automatable

**Lower Priority (Manual for Now):**
6. Test Case E - Complex multi-session scenario
7. Test Case F - Requires time manipulation or waiting until midnight
