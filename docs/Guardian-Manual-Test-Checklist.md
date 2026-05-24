# Guardian Foreground Service — Manual Test Checklist

## Setup

- Deploy `sql/migration_reward_audio_monitor_settings.sql` and updated `af_upsert_settings_row.sql` to Supabase.
- Enable BaerenLock accessibility service and usage stats.
- Confirm `GuardianForegroundService` notification shows **Tablet Rules Active**.

## Core enforcement

1. Start reward session from launcher → notification shows **Reward Time Running**.
2. Open Netflix (or another reward app) for 30+ minutes → reward apps remain allowed until expiry.
3. Let reward time expire while in Netflix → device returns to launcher within ~15s (local expiry watchdog).
4. With 0 banked minutes, try opening a blacklisted app → blocked and returned to launcher.

## Accessibility resilience

1. With reward session active, verify enforcement continues when accessibility events are sparse (UsageStats poll in Guardian).
2. Disable accessibility in system settings → within ~15s, launcher health banner reflects stale heartbeat (`ACTION_ACCESSIBILITY_STALE`).

## Process survival

1. Swipe BaerenLock away from recents → Guardian restarts (`onTaskRemoved`); notification returns.
2. Reboot device → `BootReceiver` starts Guardian; banked/reward state restored from cloud on next sync.

## Audio pause (parent report)

1. Open `reports/banked_time.html` → set threshold and enable/disable audio monitor → Save.
2. Start reward session → speak loudly near mic for 3+ seconds above threshold → reward pauses with toast.
3. Disable audio monitor in parent report → loud noise does not pause reward.

## UI commands

1. **Use Reward Time** → Guardian handles RPC; UI updates via `ACTION_REWARD_TIME_UPDATED`.
2. **Pause Reward Time** → Guardian pauses; banked minutes shown on launcher.
