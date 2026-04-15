package com.talq2me.baerenlock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WhitelistSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WhitelistSettings"
    }

    private val selectedPackages = mutableSetOf<String>()
    private var initialPackages = emptySet<String>()
    private var loaded = false
    private var contentLayout: LinearLayout? = null
    private var headerView: TextView? = null
    private var allAppsInfo: List<Pair<String, String>> = emptyList() // (pkg, label)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { saveAndFinish() }
        })
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        val loadingView = TextView(this).apply {
            text = "Loading from database..."
            setPadding(0, 0, 0, 16)
        }
        layout.addView(loadingView)
        contentLayout = layout
        setContentView(ScrollView(this).apply { addView(layout) })

        lifecycleScope.launch {
            val profile = ProfileManager.getCurrentProfile(this@WhitelistSettingsActivity)
            val fromCloud = withContext(Dispatchers.IO) { SupabaseInterface.fetchAppListsFromCloud(this@WhitelistSettingsActivity, profile) }
            val whitelistFromDb = fromCloud?.whiteListed ?: emptySet()
            initialPackages = whitelistFromDb
            selectedPackages.clear()
            selectedPackages.addAll(whitelistFromDb)

            val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val apps = launcherApps.getActivityList(null, UserHandle.getUserHandleForUid(android.os.Process.myUid()))
            val allApps = apps
                .filter { it.applicationInfo.packageName != packageName }
                .distinctBy { it.applicationInfo.packageName }
                .sortedBy { try { it.label.toString() } catch (_: Exception) { it.applicationInfo.packageName } }
            val pm = packageManager
            allAppsInfo = allApps.map { ri ->
                ri.applicationInfo.packageName to try { ri.label.toString() } catch (_: Exception) { ri.applicationInfo.packageName }
            }

            withContext(Dispatchers.Main) {
                layout.removeView(loadingView)
                val header = TextView(this@WhitelistSettingsActivity).apply {
                    text = "Found ${allAppsInfo.size} apps. Currently whitelisted: ${selectedPackages.size}"
                    setPadding(0, 0, 0, 16)
                }
                layout.addView(header)
                headerView = header
                for ((pkg, appName) in allAppsInfo) {
                    val isSystem = isSystemApp(pkg)
                    val cb = CheckBox(this@WhitelistSettingsActivity).apply {
                        text = if (isSystem && selectedPackages.contains(pkg)) "⚠️ SYSTEM: $appName ($pkg)" else "$appName ($pkg)"
                        isChecked = selectedPackages.contains(pkg)
                        setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) selectedPackages.add(pkg) else selectedPackages.remove(pkg)
                            header.text = "Found ${allAppsInfo.size} apps. Currently whitelisted: ${selectedPackages.size}"
                            text = if (isSystem && isChecked) "⚠️ SYSTEM: $appName ($pkg)" else "$appName ($pkg)"
                        }
                    }
                    layout.addView(cb)
                }
                loaded = true
            }
        }
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

    private fun saveAndFinish() {
        if (!loaded) { finish(); return }
        if (selectedPackages == initialPackages) { finish(); return }
        lifecycleScope.launch {
            val profile = ProfileManager.getCurrentProfile(this@WhitelistSettingsActivity)
            val json = com.google.gson.Gson().toJson(selectedPackages.toList())
            val ok = withContext(Dispatchers.IO) {
                SupabaseInterface.patchAppListToCloud(this@WhitelistSettingsActivity, profile, "white_listed_apps", json)
            }
            withContext(Dispatchers.Main) {
                if (ok) Toast.makeText(this@WhitelistSettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this@WhitelistSettingsActivity, "Save failed", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}

