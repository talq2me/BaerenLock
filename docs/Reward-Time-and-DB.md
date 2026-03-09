# Reward Time and DB (banked_mins) — Verification

## Does reward time still get decremented in the DB?

**Yes.** BaerenLock does **not** use the SQL RPCs (`af_update_berries_banked`, etc.) for reward minutes. It uses the **Supabase REST API**: it PATCHes the `user_data` row with `banked_mins` (and `last_updated`). The move to “lots in SQL” in BaerenEd did not change this.

- **Source of truth:** Cloud `user_data.banked_mins` (and now `reward_time_expiry`).
- **When the kid uses reward apps:**  
  `AppBlockerService` runs `checkAndUpdateRewardTime()` every 5 seconds → `RewardManager.performTimerCheck()` uses UsageStats to compute actual reward-app usage, decrements in-memory minutes, then calls `RewardStorage.saveRewardMinutes()` → `CloudSyncManager.syncRewardMinutesToCloudAsync()` → **PATCH** `user_data` with the new `banked_mins`.
- So **each minute in a reward app** is reflected as a **decrease of `banked_mins` in the DB** via this PATCH path.

## reward_time_expiry (EST)

To avoid unlimited reward time by accident, we store **when** reward time must be used by:

- **DB:** `user_data.reward_time_expiry` (TIMESTAMP(3) NULL, same as other date/time columns). Stored in EST. Client sends `yyyy-MM-dd HH:mm:ss.SSS` (America/New_York).
- **Set when:**  
  - Lock adds minutes (Intent from BaerenEd or manual add): expiry = now (EST) + new total minutes.  
  - Lock starts the reward timer with minutes but no expiry (e.g. loaded only from cloud): expiry = now (EST) + current minutes.
- **Enforced:**  
  - Allow reward apps only if `banked_mins > 0` **and** (no expiry or current time ≤ expiry).  
  - If past expiry, treat as 0: block reward apps, set minutes to 0, clear expiry, sync to cloud.
- **Reset:** `af_daily_reset` sets `reward_time_expiry = NULL`.

## Migration

Run once in Supabase SQL Editor:

- `BaerenEd/sql/migration_add_reward_time_expiry.sql` — adds `reward_time_expiry` to `user_data`.
- Ensure `af_daily_reset` is updated to clear `reward_time_expiry` (already done in `af_daily_reset.sql`).
