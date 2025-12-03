# Viewing Logs from Tablet in Android Studio

## Quick Setup

### Option 1: Using Android Studio Logcat (Recommended)

1. **Connect your tablet via USB** and ensure USB debugging is enabled
2. **Open Android Studio** and make sure your tablet is selected in the device dropdown (top of logcat window)
3. **In the Logcat window**, set up filters:

#### Filter for RewardManager logs:
   - Filter name: `RewardManager`
   - Log Tag: `RewardManager`
   - Or use regex: `RewardManager|LauncherActivity|AppBlocker`

#### Filter for all BaerenLock logs:
   - Filter name: `BaerenLock`
   - Package name: `com.talq2me.baerenlock`

#### Filter for reward time expiration:
   - Filter name: `RewardExpiration`
   - Log message contains: `expired|Reward time|Forced return`

### Option 2: Using Command Line (More Control)

#### View logs in real-time (filtered for reward-related):
```bash
adb -s 1483PRC230100221 logcat -s RewardManager:D LauncherActivity:D AppBlocker:D
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

### What to Look For When Testing Reward Expiration:

1. `RewardManager: Reward time expired. Temporary apps removed.`
2. `RewardManager: Forced return to BaerenLock launcher`
3. `RewardManager: Launched HOME intent to return to BaerenLock launcher`
4. `RewardManager: Launched LauncherActivity directly as backup`
5. `AppBlocker: Reward app with 0 minutes, blocking: [package]`

## Quick Test Command

To test if logging is working, run this and then trigger reward expiration:

```bash
adb -s 1483PRC230100221 logcat -s RewardManager:D | grep -i "expired\|return\|launch"
```

This will show only reward expiration and return-to-launcher messages in real-time.

