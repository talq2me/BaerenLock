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
import android.content.pm.ResolveInfo
import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle


class WhitelistSettingsActivity : AppCompatActivity() {

    private val allowed = RewardManager.allowedApps

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val pm = packageManager
        val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        
        // Get all apps that can be launched (like KidsPlace does)
        val apps = launcherApps.getActivityList(null, UserHandle.getUserHandleForUid(android.os.Process.myUid()))
        
        Log.d("WhitelistSettings", "LauncherApps found: ${apps.size}")
        
        // Show all launcher apps except our own app
        val allApps = apps
            .filter { app ->
                // Only exclude our own app
                val isOurApp = app.applicationInfo.packageName == packageName
                if (isOurApp) {
                    Log.d("WhitelistSettings", "Filtering out our own app: ${app.applicationInfo.packageName}")
                }
                !isOurApp
            }
            .distinctBy { it.applicationInfo.packageName } // Remove duplicates
            .sortedBy { app ->
                try {
                    app.label.toString()
                } catch (e: Exception) {
                    app.applicationInfo.packageName
                }
            }

        Log.d("WhitelistSettings", "Apps to show: ${allApps.size}")
        
        // Debug: Show first 20 apps to see what we're getting
        allApps.take(20).forEach { app ->
            Log.d("WhitelistSettings", "App: ${app.label} (${app.applicationInfo.packageName})")
        }
        
        // Count only the apps that are actually in the whitelist
        val whitelistedCount = allApps.count { app ->
            allowed.contains(app.applicationInfo.packageName)
        }
        
        Log.d("WhitelistSettings", "Currently whitelisted apps: $whitelistedCount")

        // Add header showing counts
        val header = TextView(this).apply {
            text = "Found ${allApps.size} apps. Currently whitelisted: $whitelistedCount"
            setPadding(0, 0, 0, 16)
        }
        layout.addView(header)

        for (app in allApps) {
            val pkg = app.applicationInfo.packageName
            val appName = try {
                app.label.toString()
            } catch (e: Exception) {
                pkg
            }
            
            Log.d("WhitelistSettings", "Adding app: $appName ($pkg)")
            
            val isSystem = isSystemApp(pkg)
            val cb = CheckBox(this).apply {
                text = if (isSystem && allowed.contains(pkg)) {
                    "⚠️ SYSTEM: $appName ($pkg)"
                } else {
                    "$appName ($pkg)"
                }
                isChecked = allowed.contains(pkg)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        RewardManager.addToWhitelist(pkg, this@WhitelistSettingsActivity)
                        Log.d("WhitelistSettings", "Added to whitelist: $pkg")
                    } else {
                        RewardManager.removeFromWhitelist(pkg, this@WhitelistSettingsActivity)
                        Log.d("WhitelistSettings", "Removed from whitelist: $pkg")
                    }
                    // Update the header count and refresh text if it's a system app
                    val newCount = allApps.count { ri -> allowed.contains(ri.applicationInfo.packageName) }
                    header.text = "Found ${allApps.size} apps. Currently whitelisted: $newCount"
                    // Update checkbox text to show/hide system indicator
                    text = if (isSystem && isChecked) {
                        "⚠️ SYSTEM: $appName ($pkg)"
                    } else {
                        "$appName ($pkg)"
                    }
                }
            }
            layout.addView(cb)
        }

        val scroll = ScrollView(this).apply {
            addView(layout)
        }

        setContentView(scroll)
    }

    private fun isSystemApp(pkgName: String): Boolean {
        // Match the same list as AppBlockerService.shouldBlockApp()
        return pkgName.startsWith("com.android.systemui") ||
               pkgName.startsWith("com.android.launcher") ||
               pkgName.startsWith("com.google.android.apps.nexuslauncher") ||
               pkgName.startsWith("com.google.android.launcher") ||
               pkgName.startsWith("com.android.phone") ||
               pkgName == "com.android.settings" ||
               pkgName.startsWith("com.android.providers.") ||
               pkgName.startsWith("com.android.packageinstaller") ||
               pkgName.startsWith("com.google.android.packageinstaller") ||
               pkgName.startsWith("com.google.android.inputmethod") ||
               pkgName.startsWith("com.android.inputmethod") ||
               pkgName.startsWith("com.samsung.inputmethod") ||
               pkgName.startsWith("com.google.android.apps.inputmethod") ||
               pkgName.startsWith("com.google.android.gms") ||
               pkgName.startsWith("com.google.android.gsf") ||
               pkgName.startsWith("com.google.android.setupwizard") ||
               pkgName.startsWith("com.android.permissioncontroller") ||
               pkgName.startsWith("com.google.android.permissioncontroller") ||
               pkgName == "android" ||
               pkgName == "com.android.server.telecom" ||
               pkgName == "com.android.dialer" ||
               pkgName.startsWith("com.android.emergency") ||
               pkgName.startsWith("com.android.certinstaller") ||
               pkgName.startsWith("com.google.android.certinstaller")
    }

    override fun onPause() {
        super.onPause()
        RewardManager.saveAllowedApps(this)
    }
}

