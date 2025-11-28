package com.talq2me.baerenlock

import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.content.pm.PackageManager
import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle

class BlackListSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BlackListSettings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val pm = packageManager
        
        // Get all installed packages (including system apps) so we can manage everything
        val installedPackages = pm.getInstalledPackages(0)
        
        // Also get launcher apps for labels/icons
        val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val launcherAppList = launcherApps.getActivityList(null, UserHandle.getUserHandleForUid(android.os.Process.myUid()))
        val launcherAppMap = launcherAppList.associateBy { it.applicationInfo.packageName }
        
        Log.d(TAG, "Installed packages found: ${installedPackages.size}")
        Log.d(TAG, "LauncherApps found: ${launcherAppList.size}")
        
        // Create a list of all apps with their info
        data class AppInfo(val packageName: String, val label: String)
        
        val allApps = installedPackages
            .filter { it.packageName != packageName && it.applicationInfo != null } // Exclude our own app and null applicationInfo
            .map { pkgInfo ->
                val pkgName = pkgInfo.packageName
                val launcherInfo = launcherAppMap[pkgName]
                val label = try {
                    launcherInfo?.label?.toString() 
                        ?: (pkgInfo.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkgName)
                } catch (e: Exception) {
                    pkgName
                }
                AppInfo(pkgName, label)
            }
            .sortedBy { it.label }

        Log.d(TAG, "Apps to show: ${allApps.size}")
        
        // Get current blacklist
        val blacklist = getBlacklistFromService().toMutableSet()
        
        // Count apps that are currently blacklisted
        val blacklistedCount = allApps.count { app ->
            blacklist.contains(app.packageName)
        }
        
        Log.d(TAG, "Currently blacklisted apps: $blacklistedCount")

        // Add header showing counts
        val header = TextView(this).apply {
            text = "Found ${allApps.size} apps. Currently blacklisted: $blacklistedCount"
            setPadding(0, 0, 0, 16)
        }
        layout.addView(header)
        
        // Add "Clear All" button to remove all blacklist entries
        val clearAllButton = android.widget.Button(this).apply {
            text = "Clear All Blacklist Entries"
            setOnClickListener {
                clearAllBlacklist()
                // Refresh the activity
                finish()
                startActivity(Intent(this@BlackListSettingsActivity, BlackListSettingsActivity::class.java))
            }
        }
        layout.addView(clearAllButton)

        for (app in allApps) {
            val pkg = app.packageName
            val appName = app.label
            
            Log.d(TAG, "Adding app: $appName ($pkg)")
            
            val cb = CheckBox(this).apply {
                text = "$appName ($pkg)"
                isChecked = blacklist.contains(pkg)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        // Add to blacklist
                        addToBlacklist(pkg)
                        // Remove from whitelist if it's there
                        if (RewardManager.allowedApps.contains(pkg)) {
                            RewardManager.removeFromWhitelist(pkg, this@BlackListSettingsActivity)
                            Log.d(TAG, "Removed $pkg from whitelist (now blacklisted)")
                        }
                        // Remove from reward list if it's there
                        if (RewardManager.rewardEligibleApps.contains(pkg)) {
                            RewardManager.rewardEligibleApps.remove(pkg)
                            // Save reward apps
                            val rewardPrefs = getSharedPreferences("settings", MODE_PRIVATE)
                            val currentRewardApps = rewardPrefs.getStringSet("reward_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
                            currentRewardApps.remove(pkg)
                            rewardPrefs.edit().putStringSet("reward_apps", currentRewardApps).apply()
                            RewardManager.refreshRewardEligibleApps(this@BlackListSettingsActivity)
                            Log.d(TAG, "Removed $pkg from reward list (now blacklisted)")
                        }
                        Log.d(TAG, "Added to blacklist: $pkg")
                    } else {
                        // Remove from blacklist
                        removeFromBlacklist(pkg)
                        Log.d(TAG, "Removed from blacklist: $pkg")
                    }
                    // Update the header count
                    val newCount = allApps.count { app -> 
                        getBlacklistFromService().contains(app.packageName) 
                    }
                    header.text = "Found ${allApps.size} apps. Currently blacklisted: $newCount"
                }
            }
            layout.addView(cb)
        }

        val scroll = ScrollView(this).apply {
            addView(layout)
        }

        setContentView(scroll)
    }

    private fun getBlacklistFromService(): Set<String> {
        // Read directly from SharedPreferences (same storage AppBlockerService uses)
        val prefs = getSharedPreferences("blacklist_prefs", MODE_PRIVATE)
        return prefs.getStringSet("packages", emptySet()) ?: emptySet()
    }

    private fun addToBlacklist(pkgName: String) {
        val prefs = getSharedPreferences("blacklist_prefs", MODE_PRIVATE)
        val blacklist = prefs.getStringSet("packages", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        blacklist.add(pkgName)
        prefs.edit().putStringSet("packages", blacklist).apply()
        Log.d(TAG, "Added $pkgName to blacklist")
    }

    private fun removeFromBlacklist(pkgName: String) {
        val prefs = getSharedPreferences("blacklist_prefs", MODE_PRIVATE)
        val blacklist = prefs.getStringSet("packages", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        blacklist.remove(pkgName)
        prefs.edit().putStringSet("packages", blacklist).apply()
        Log.d(TAG, "Removed $pkgName from blacklist")
    }
    
    private fun clearAllBlacklist() {
        val prefs = getSharedPreferences("blacklist_prefs", MODE_PRIVATE)
        prefs.edit().remove("packages").apply()
        Log.d(TAG, "Cleared all blacklist entries")
    }
}
