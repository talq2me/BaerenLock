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

class BlackListSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BlackListSettings"
    }

    private val selectedPackages = mutableSetOf<String>()
    private var initialPackages = emptySet<String>()
    private var loaded = false
    private var headerView: TextView? = null
    private var checkboxes = mutableListOf<CheckBox>()
    private var appPackages = listOf<String>()

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
        setContentView(ScrollView(this).apply { addView(layout) })

        lifecycleScope.launch {
            val profile = ProfileManager.getCurrentProfile(this@BlackListSettingsActivity)
            val fromCloud = withContext(Dispatchers.IO) { SupabaseInterface.fetchAppListsFromCloud(this@BlackListSettingsActivity, profile) }
            val blacklistFromDb = fromCloud?.blacklisted ?: emptySet()
            initialPackages = blacklistFromDb
            selectedPackages.clear()
            selectedPackages.addAll(blacklistFromDb)

            val pm = packageManager
            val installedPackages = pm.getInstalledPackages(0)
            val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val launcherAppList = launcherApps.getActivityList(null, UserHandle.getUserHandleForUid(android.os.Process.myUid()))
            val launcherAppMap = launcherAppList.associateBy { it.applicationInfo.packageName }
            data class AppInfo(val packageName: String, val label: String)
            val allApps = installedPackages
                .filter { it.packageName != packageName && it.applicationInfo != null }
                .map { pkgInfo ->
                    val pkgName = pkgInfo.packageName
                    val label = try {
                        launcherAppMap[pkgName]?.label?.toString()
                            ?: (pkgInfo.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkgName)
                    } catch (_: Exception) { pkgName }
                    AppInfo(pkgName, label)
                }
                .sortedBy { it.label }
            appPackages = allApps.map { it.packageName }

            withContext(Dispatchers.Main) {
                layout.removeView(loadingView)
                val header = TextView(this@BlackListSettingsActivity).apply {
                    text = "Found ${allApps.size} apps. Currently blacklisted: ${selectedPackages.size}"
                    setPadding(0, 0, 0, 16)
                }
                layout.addView(header)
                headerView = header
                layout.addView(android.widget.Button(this@BlackListSettingsActivity).apply {
                    text = "Clear All Blacklist Entries"
                    setOnClickListener {
                        selectedPackages.clear()
                        checkboxes.forEach { it.isChecked = false }
                        headerView?.text = "Found ${appPackages.size} apps. Currently blacklisted: 0"
                    }
                })
                checkboxes.clear()
                for (app in allApps) {
                    val pkg = app.packageName
                    val cb = CheckBox(this@BlackListSettingsActivity).apply {
                        text = "${app.label} ($pkg)"
                        isChecked = selectedPackages.contains(pkg)
                        setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) selectedPackages.add(pkg) else selectedPackages.remove(pkg)
                            headerView?.text = "Found ${appPackages.size} apps. Currently blacklisted: ${selectedPackages.size}"
                        }
                    }
                    layout.addView(cb)
                    checkboxes.add(cb)
                }
                loaded = true
            }
        }
    }

    private fun saveAndFinish() {
        if (!loaded) { finish(); return }
        if (selectedPackages == initialPackages) { finish(); return }
        lifecycleScope.launch {
            val profile = ProfileManager.getCurrentProfile(this@BlackListSettingsActivity)
            val json = com.google.gson.Gson().toJson(selectedPackages.toList())
            val ok = withContext(Dispatchers.IO) {
                SupabaseInterface.patchAppListToCloud(this@BlackListSettingsActivity, profile, "blacklisted_apps", json)
            }
            withContext(Dispatchers.Main) {
                if (ok) Toast.makeText(this@BlackListSettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this@BlackListSettingsActivity, "Save failed", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
