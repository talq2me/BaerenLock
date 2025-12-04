# Viewing Logs from Tablet in Android Studio

## Quick Setup

### Option 1: Using Android Studio Logcat (Recommended)

1. **Connect your tablet via USB** and ensure USB debugging is enabled
2. **Open Android Studio** and make sure your tablet is selected in the device dropdown (top of logcat window)
3. **In the Logcat window**, set up filters:

#### Filter for RewardManager logs:
   - Filter name: `RewardManager`
   - Log Tag: `RewardManager`
   - Or use regex: `RewardManager|LauncherActivity|AppBlocker|MainActivity|RewardUsageTracker`

#### Filter for all BaerenLock logs:
   - Filter name: `BaerenLock`
   - Package name: `com.talq2me.baerenlock`

#### Filter for reward time expiration:
   - Filter name: `RewardExpiration`
   - Log message contains: `expired|Reward time|Forced return`

### Option 2: Using Command Line (More Control)

#### View logs in real-time (filtered for reward-related):
```bash
adb -s 1483PRC230100221 logcat -s RewardManager:D LauncherActivity:D AppBlocker:D MainActivity:D RewardUsageTracker:D
```

#### View logs specifically for report generation:
```bash
adb -s 1483PRC230100221 logcat -s RewardManager:D MainActivity:D RewardUsageTracker:D | grep -i "report\|tracking\|upload\|session"
```

#### View all BaerenLock logs:
```bash
adb -s 1483PRC230100221 logcat | grep -i "baerenlock\|reward"
```

#### View logs and save to file:
```bash
adb -s 1483PRC230100221 logcat > tablet_logs.txt
```

#### Clear logs and start fresh:
```bash
adb -s 1483PRC230100221 logcat -c
adb -s 1483PRC230100221 logcat -s RewardManager:D LauncherActivity:D AppBlocker:D
```

### Option 3: Using Android Studio Device File Explorer

1. **View > Tool Windows > Device File Explorer**
2. Navigate to `/data/local/tmp/` or check if logs are being written to a file

## Troubleshooting

### If you don't see logs in Android Studio:

1. **Check device selection**: Make sure your tablet (1483PRC230100221) is selected in the device dropdown
2. **Check USB debugging**: Settings > Developer Options > USB Debugging (must be ON)
3. **Restart ADB**: 
   ```bash
   adb kill-server
   adb start-server
   adb devices
   ```
4. **Check logcat level**: Make sure it's set to "Verbose" or "Debug" (not "Error" or "Warn")
5. **Clear logcat buffer**: Click the trash icon in logcat or use `adb logcat -c`

### Key Log Tags to Monitor:

- `RewardManager` - All reward time management
- `LauncherActivity` - Launcher activity events
- `AppBlocker` - App blocking service
- `RewardTimeReceiver` - Reward time broadcasts
- `MainActivity` - Main activity and report upload
- `RewardUsageTracker` - Reward app usage tracking

### What to Look For When Testing Reward Expiration:

1. `RewardManager: Reward time expired. Temporary apps removed.`
2. `RewardManager: Forced return to BaerenLock launcher`
3. `RewardManager: Launched HOME intent to return to BaerenLock launcher`
4. `RewardManager: Launched LauncherActivity directly as backup`
5. `AppBlocker: Reward app with 0 minutes, blocking: [package]`

### What to Look For When Testing Report Generation:

1. **When reward time starts:**
   - `RewardManager: Started reward session tracking`
   - `RewardUsageTracker: Started reward session tracking`

2. **During reward time (when apps are used):**
   - `RewardManager: Tracking foreground app during reward time: [package]`
   - `RewardUsageTracker: Started tracking app: [app name] ([package])`
   - `RewardUsageTracker: Ended tracking app: [app name] - Duration: [time]`

3. **When reward time expires:**
   - `RewardManager: Reward time expired. Usage data: available ([X] sessions)`
   - `RewardManager: Usage data available: [X] sessions, total time: [X]s`
   - `RewardManager: Sent ACTION_GENERATE_REWARD_REPORT broadcast via LocalBroadcastManager`
   - `RewardManager: Started MainActivity to ensure report upload can happen`
   - `RewardManager: Ended reward session tracking. Sessions: [X]`
   - `RewardUsageTracker: Ended reward session. Total apps tracked: [X]`

4. **When report is received and uploaded:**
   - `MainActivity: rewardReportReceiver.onReceive called with action: com.talq2me.baerenlock.ACTION_GENERATE_REWARD_REPORT`
   - `MainActivity: Received ACTION_GENERATE_REWARD_REPORT broadcast`
   - `MainActivity: Usage data check: sessions=true ([X] sessions), summary=true`
   - `MainActivity: Generating and uploading reward report with [X] sessions`
   - `MainActivity: generateAndUploadRewardReport called with [X] sessions, total time: [X]s`
   - `MainActivity: GitHub token decrypted successfully, proceeding with upload`
   - `MainActivity: Report uploaded successfully to GitHub: BaerenEd_Reports/[AM|BM]_Rewards_Usage.txt`

5. **If something goes wrong:**
   - `RewardManager: No usage data available - skipping report generation` (means tracking didn't work)
   - `MainActivity: Cannot generate report: sessions or summary is null` (means data wasn't stored)
   - `MainActivity: GitHub token not configured - skipping upload` (means token is missing)
   - `MainActivity: GitHub upload failed: [error]` (means upload failed)

## Quick Test Command

To test if logging is working, run this and then trigger reward expiration:

```bash
adb -s 1483PRC230100221 logcat -s RewardManager:D | grep -i "expired\|return\|launch"
```

This will show only reward expiration and return-to-launcher messages in real-time.

