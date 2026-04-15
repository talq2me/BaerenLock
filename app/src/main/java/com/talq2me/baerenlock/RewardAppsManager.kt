package com.talq2me.baerenlock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manages reward-eligible apps and whitelist management.
 * Handles loading/saving reward apps, whitelist operations, and app visibility.
 */
object RewardAppsManager {
    private const val TAG = "RewardAppsManager"
    private const val WHITELIST_PREFS_NAME = "whitelist_prefs"
    private const val KEY_ALLOWED = "allowed"
    
    // Fixed set of apps allowed to run in the background (memory control)
    val memoryAllowedApps = setOf(
        "com.talq2me.baerenlock",
        "com.talq2me.baerened",
        "com.nianticlabs.pokemongo" // Pokemon GO is always allowed in memory
    )
    
    // Essential system packages that should never be killed
    val essentialSystemPackages = setOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "android",
        "com.android.phone",
        "com.android.settings",
        "com.android.providers.settings",
        "com.android.providers.downloads",
        "com.android.providers.media",
        "com.android.providers.calendar",
        "com.android.providers.contacts",
        "com.android.packageinstaller", // For app installation/permissions
        "com.google.android.packageinstaller", // Google's package installer
        "com.android.vending", // Google Play Store
        "com.google.android.settings.intelligence" // Google-specific settings intelligence
    )
    
    // Permanently whitelisted apps (visible in launcher)
    private val allowedApps = mutableSetOf(
        "com.talq2me.baerened", // BaerenEd app
        "com.talq2me.baerenlock" // BaerenLock launcher
    )
    
    // Temporarily allowed apps (for grantAccess functionality)
    private val temporaryApps = mutableSetOf<String>()
    
    // User-configured reward-eligible apps
    private val rewardEligibleApps = mutableSetOf<String>()
    
    /**
     * Checks if an app is allowed (whitelisted, temporary, or reward-eligible with active minutes)
     */
    fun isAllowed(pkg: String, rewardSessionActive: Boolean): Boolean {
        // An app is allowed if it's permanently whitelisted, temporarily allowed, OR
        // if it's a reward-eligible app AND reward minutes are currently active.
        return allowedApps.contains(pkg) ||
               temporaryApps.contains(pkg) ||
               (rewardEligibleApps.contains(pkg) && rewardSessionActive)
    }
    
    /**
     * Adds an app to the permanent whitelist
     */
    fun addToWhitelist(pkg: String, context: Context) {
        allowedApps.add(pkg)
        saveAllowedApps(context)
        // Automatically remove from blacklist when whitelisted
        BlacklistManager.removeFromBlacklist(context, pkg)
        Log.d(TAG, "Added $pkg to whitelist")
    }
    
    /**
     * Removes an app from the permanent whitelist
     */
    fun removeFromWhitelist(pkg: String, context: Context) {
        allowedApps.remove(pkg)
        saveAllowedApps(context)
        Log.d(TAG, "Removed $pkg from whitelist")
    }
    
    /**
     * Temporarily grants access to an app (for grantAccess functionality)
     */
    fun grantTemporaryAccess(pkg: String, context: Context) {
        allowedApps.add(pkg)
        temporaryApps.add(pkg)
        saveAllowedApps(context)
        Log.d(TAG, "Granted temporary access to $pkg")
    }
    
    /**
     * Revokes temporary access to an app
     */
    fun revokeTemporaryAccess(pkg: String, context: Context) {
        allowedApps.remove(pkg)
        temporaryApps.remove(pkg)
        saveAllowedApps(context)
        Log.d(TAG, "Revoked temporary access from $pkg")
    }
    
    /**
     * Clears all temporary apps
     */
    fun clearTemporaryApps(context: Context) {
        allowedApps.removeAll(temporaryApps)
        temporaryApps.clear()
        saveAllowedApps(context)
        Log.d(TAG, "Cleared all temporary apps")
    }
    
    /**
     * Saves allowed apps to local storage and syncs to cloud
     */
    fun saveAllowedApps(context: Context) {
        val prefs = context.getSharedPreferences(WHITELIST_PREFS_NAME, Context.MODE_PRIVATE)
        // Only save permanent apps, not temporary reward apps
        val permanentApps = allowedApps.filter { !temporaryApps.contains(it) }.toSet()
        prefs.edit().putStringSet(KEY_ALLOWED, permanentApps).apply()
        
        // Sync to cloud
        SupabaseInterface.syncAppListsToCloudAsync(context)
        Log.d(TAG, "Saved allowed apps: $permanentApps")
    }
    
    /**
     * Loads allowed apps from local storage
     */
    fun loadAllowedApps(context: Context) {
        val prefs = context.getSharedPreferences(WHITELIST_PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_ALLOWED, null)
        if (stored != null) {
            allowedApps.clear()
            allowedApps.addAll(stored)
            // Don't reload temporary apps from storage
        }

        // Always add PokemonGo if it's installed (for launcher display)
        if (isPackageInstalled(context, "com.nianticlabs.pokemongo")) {
            allowedApps.add("com.nianticlabs.pokemongo")
        }

        // Also add Baeren (web app) if it's installed (for launcher display)
        if (isPackageInstalled(context, "com.talq2me.baeren")) {
            allowedApps.add("com.talq2me.baeren")
        }
        
        Log.d(TAG, "Loaded allowed apps: $allowedApps")
    }
    
    /**
     * Loads reward-eligible apps from SettingsManager
     */
    fun loadRewardEligibleApps(context: Context) {
        val savedRewardApps = SettingsManager.readRewardApps(context)
        rewardEligibleApps.clear()
        rewardEligibleApps.addAll(savedRewardApps)
        Log.d(TAG, "Loaded reward eligible apps: $rewardEligibleApps")
    }
    
    /**
     * Refreshes reward-eligible apps from SettingsManager
     * Removes newly added reward apps from blacklist
     */
    fun refreshRewardEligibleApps(context: Context) {
        val savedRewardApps = SettingsManager.readRewardApps(context)
        val oldRewardApps = rewardEligibleApps.toSet()
        rewardEligibleApps.clear()
        rewardEligibleApps.addAll(savedRewardApps)
        
        // Remove newly added reward apps from blacklist
        savedRewardApps.forEach { pkg ->
            if (!oldRewardApps.contains(pkg)) {
                BlacklistManager.removeFromBlacklist(context, pkg)
            }
        }
        
        Log.d(TAG, "Refreshed reward eligible apps: $rewardEligibleApps")
    }
    
    /**
     * Gets the set of reward-eligible apps
     */
    fun getRewardEligibleApps(): Set<String> {
        return rewardEligibleApps.toSet()
    }
    
    /**
     * Gets the set of allowed apps (for launcher display)
     */
    fun getAllowedAppsList(): Set<String> {
        // This returns the set of apps that should be visible in the launcher's whitelist
        // It includes permanently allowed apps and currently active temporary reward apps.
        return allowedApps.toSet()
    }
    
    /**
     * Adds PokemonGo to allowed apps if installed
     */
    fun addPokemonGoIfInstalled(context: Context) {
        // This function adds PokemonGo to the launcher's 'allowedApps' (if it's not a reward app)
        // It does not affect memoryAllowedApps as that's a fixed set.
        if (isPackageInstalled(context, "com.nianticlabs.pokemongo")) {
            allowedApps.add("com.nianticlabs.pokemongo")
            Log.d(TAG, "PokemonGo is installed and added to allowed apps for launcher display")
        }
    }
    
    /**
     * Checks if a package is installed
     */
    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Checks if a background app is allowed (for memory management)
     */
    fun isBackgroundAppAllowed(packageName: String): Boolean {
        // Allow essential system packages and our memory-whitelisted apps
        return essentialSystemPackages.contains(packageName) ||
               packageName.startsWith("com.android.") ||
               packageName.startsWith("android.") ||
               memoryAllowedApps.contains(packageName) // Check against the fixed memory whitelist
    }
}
