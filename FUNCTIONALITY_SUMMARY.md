# BaerenLock Functionality Summary

## Overview
BaerenLock is a child safety launcher and app blocker for Android tablets. It functions as the default launcher and uses accessibility services and device policy management to restrict app access and manage reward time.

---

## Core Features

### 1. Launcher Functionality (LauncherActivity)
**Purpose**: Main home screen that serves as the default Android launcher

**Features**:
- Displays installed apps in a grid
- Shows daily background image based on profile (A/B)
- Shows reward minutes remaining
- Cloud storage toggle
- Settings button
- PIN prompt for accessing settings
- Health check monitoring
- App launching

**Dependencies**:
- RewardManager (for reward minutes)
- SettingsManager (for profile, cloud sync)
- ServiceHealthMonitor (for health checks)
- AppBlockerService (indirectly - checks if enabled)

---

### 2. App Blocking (AppBlockerService)
**Purpose**: Accessibility service that monitors and blocks unauthorized apps

**How it works**:
- Monitors accessibility events (window state changes)
- Blocks apps in blacklist
- Blocks reward apps when reward time expires
- Special case: Allows Chrome if viewing JeLis (French reading app) or launched from BaerenEd
- Uses periodic checks (ActivityManager) and UsageStats as backup
- Kills unauthorized background apps periodically
- Health monitoring: Tracks if service is receiving events

**Blocking logic**:
1. Never blocks own app (BaerenLock)
2. Blocks if app is in blacklist (except Chrome for JeLis)
3. Blocks if app is reward-eligible and reward minutes = 0
4. Everything else is allowed

**Dependencies**:
- RewardManager (for reward apps/minutes)
- DevicePolicyManager (for stronger blocking if device owner)
- LauncherActivity (to return home)
- SettingsManager (for blacklist storage - via SharedPreferences)

---

### 3. Reward System (RewardManager)
**Purpose**: Manages reward time and reward-eligible apps

**Features**:
- Tracks current reward minutes
- Manages reward-eligible apps list
- Timer that decrements reward minutes
- Uses UsageStats to track actual usage vs timer
- Kills unauthorized background apps
- Reward session tracking (what apps used during reward time)
- Syncs reward minutes to cloud (per profile)
- Generates reward usage reports
- Handles reward time expiration (kills apps, returns to launcher)

**Dependencies**:
- SettingsManager (for cloud sync, app lists)
- RewardUsageTracker (for usage tracking)
- RewardReportGenerator (for reports)
- RewardTimeReceiver (for external reward time additions)

---

### 4. Settings & Cloud Sync (SettingsManager)
**Purpose**: Manages local and cloud settings synchronization

**Features**:
- Profile management (A/B, stored locally only)
- PIN management (synced to cloud)
- Parent email (synced to cloud)
- Child name (local only)
- Reward apps (local only, synced via user_data table)
- Aggressive cleanup setting (cloud)
- Health check sync (per profile to user_data table)
- User data sync (reward minutes, app lists per profile)

**Storage**:
- Settings table: pin, parent_email, aggressive_cleanup
- User_data table: reward_apps, blacklisted_apps, white_listed_apps, banked_mins, health checks (per profile AM/BM)
- Local SharedPreferences: profile, child_name, app lists

**Dependencies**:
- Supabase API
- Local SharedPreferences

---

### 5. Health Monitoring (ServiceHealthMonitor + LauncherActivity)
**Purpose**: Detects when accessibility service or permissions aren't working

**Features**:
- Checks if accessibility service is enabled in settings
- Checks if service is actually receiving events (via AppBlockerService)
- Checks usage stats permission
- Syncs health status to cloud (per profile)
- Displays health status in parent reports

**How detection works**:
- AppBlockerService tracks last event time
- If no events in 2 minutes, marks as unhealthy
- LauncherActivity checks both "enabled" and "receiving events"
- If enabled but not receiving events → unhealthy (detects crash scenario)

**Dependencies**:
- AppBlockerService (for event tracking)
- SettingsManager (for cloud sync)

---

### 6. Device Policy Management (DevicePolicyManager)
**Purpose**: Uses Android Device Policy to provide stronger app blocking

**Features**:
- Enables/disables apps (requires device owner)
- Checks if device owner is active
- Singleton pattern

**Usage**:
- Used by AppBlockerService for stronger blocking
- Optional feature (works without device owner, just less strong)

**Dependencies**:
- DeviceAdminReceiver

---

### 7. App Lists Management
**Three types of app lists**:

**a. Blacklist (BlackListSettingsActivity)**
- Apps that should always be blocked
- Stored in SharedPreferences "blacklist_prefs"
- Synced to cloud user_data table as "blacklisted_apps"

**b. Whitelist (WhitelistSettingsActivity)**
- Apps that should be shown in the launcher AND NOT killed in background cleanup
- Stored in SharedPreferences "whitelist_prefs"
- Synced to cloud user_data table as "white_listed_apps"
- **USED BY**: LauncherActivity (via RewardManager.isAllowed() to determine which apps to display)
- **USED BY**: RewardManager (for background app cleanup - skips whitelisted apps)
- **NOT used by**: AppBlockerService (blocking logic doesn't check whitelist - uses opt-in blocking)

**c. Reward Apps (RewardAppsSettingsActivity)**
- Apps that can be used during reward time
- Stored in SettingsManager local prefs
- Synced to cloud user_data table as "reward_apps"

**Note**: AppBlockerService uses opt-in blocking (only blocks blacklist + expired rewards). Whitelist is used for launcher display (via RewardManager.isAllowed()) and background app cleanup, but NOT for blocking decisions.

---

### 8. Settings Activities
**SettingsActivity**: Main settings menu
**ChangePinActivity**: Change PIN
**ProfileSelectionActivity**: Select profile (A/B) - may be unused?

---

### 9. MainActivity
**Purpose**: WebView-based activity for viewing reports/updates

**Features**:
- WebView for displaying content
- Text-to-Speech support
- Update download capability
- Receives broadcasts for reward reports

---

### 10. Reward Reporting (RewardReportGenerator, RewardUsageTracker, RewardTimeReceiver)
**Purpose**: Track and report reward usage

**RewardUsageTracker**:
- Tracks which apps used during reward time
- Records session data (app, duration)

**RewardReportGenerator**:
- Generates usage reports
- Formats data for display/upload

**RewardTimeReceiver**:
- Receives external broadcasts to add reward time
- Action: ACTION_ADD_REWARD_TIME

---

## Potentially Unused/Unclear Components

### 1. ProfileFileManager + ProfileSelectionActivity (LEGACY/UNUSED)
- **PROBLEM**: Uses file-based storage (Documents/Baeren/baeren_profile.txt)
- LauncherActivity uses SettingsManager.readProfile() (SharedPreferences-based)
- ProfileSelectionActivity writes to file, but LauncherActivity reads from SharedPreferences
- **Two different storage mechanisms** - ProfileSelectionActivity likely doesn't work correctly
- Profile selection is done in LauncherActivity settings menu instead
- **RECOMMENDATION**: Remove ProfileFileManager and ProfileSelectionActivity, or migrate to SettingsManager

### 2. WhitelistSettingsActivity
- **USED**: Whitelist is stored, synced, and managed
- **Used by**: LauncherActivity (via RewardManager.isAllowed() to filter which apps to show)
- **Used by**: RewardManager (for background app cleanup - skips whitelisted apps)
- **NOT used by**: AppBlockerService (blocking logic doesn't check whitelist - uses opt-in blocking)
- Clarification: Whitelist controls launcher display AND background cleanup, but NOT blocking decisions

### 3. InstallResultReceiver
- **USED**: Used by MainActivity for update installation feedback
- Shows toast when update install succeeds/fails
- Part of update system

### 4. DeviceOwnerSetupActivity / DeviceOwnerSetupManager
- **SETUP ONLY**: Device owner setup functionality
- Only used during initial device setup/provisioning
- Not part of normal operation flow

### 5. DeviceRestrictionsActivity / RestrictionsManager
- **USED**: Device restriction management UI
- Accessible from SettingsActivity
- Uses RestrictionsManager to enable/disable device restrictions
- Part of device policy features

### 6. AppManagementActivity
- **USED**: App management UI
- Accessible from SettingsActivity
- Allows enabling/disabling apps, kiosk mode
- Uses KioskModeManager and DevicePolicyManager

### 7. KioskModeManager
- **USED**: Kiosk mode functionality
- Used by AppManagementActivity
- Uses RestrictionsManager to apply restrictions
- Singleton pattern

### 8. PinPromptDialog
- **USED**: PIN prompt dialog utility
- Used by MainActivity (for accessing content)
- Used by ChangePinActivity (for PIN entry)
- Reusable component

---

## Feature Coupling & Dependencies

### High Coupling Areas:

1. **RewardManager ↔ SettingsManager**
   - RewardManager syncs to cloud via SettingsManager
   - SettingsManager stores reward apps locally
   - Tight coupling

2. **AppBlockerService ↔ RewardManager**
   - AppBlockerService checks RewardManager for reward apps/minutes
   - AppBlockerService updates RewardManager with foreground app
   - Tight coupling

3. **LauncherActivity ↔ Multiple Managers**
   - Uses RewardManager, SettingsManager, ServiceHealthMonitor
   - Central hub with many dependencies

4. **SettingsManager ↔ Cloud Storage**
   - Handles both local and cloud sync
   - Mixed responsibilities

### Well-Isolated:
- ServiceHealthMonitor (standalone utility)
- DevicePolicyManager (standalone, optional)
- Individual Settings Activities (isolated)

---

## Recommendations for Isolation

1. **Separate Cloud Sync from Settings Management**
   - Create CloudSyncManager for cloud operations
   - SettingsManager handles only local settings
   - Reduces coupling

2. **Extract Blacklist Logic**
   - Create BlacklistManager
   - AppBlockerService uses BlacklistManager instead of direct SharedPreferences
   - Better testability

3. **Profile Management**
   - Create ProfileManager
   - Centralize profile logic (A/B ↔ AM/BM conversion)
   - Reduce duplication

4. **Reward System**
   - Consider splitting RewardManager into:
     - RewardTimer (time management)
     - RewardAppsManager (app list)
     - RewardUsageTracker (already separate)

5. **Remove/Clarify Unused Components**
   - Audit and remove unused activities/managers
   - Document what each component does
   - Reduce codebase complexity
