package com.talq2me.baerenlock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class AppManagementActivity : AppCompatActivity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var packageManager: PackageManager
    private lateinit var installedAppsAdapter: AppListAdapter
    private val installedApps = mutableListOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize managers
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        packageManager = this.packageManager

        // Check if device owner is active
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) {
            Toast.makeText(this, "Device Owner not active. App management features may not work.", Toast.LENGTH_LONG).show()
        }

        // Create UI programmatically
        val scrollView = ScrollView(this)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        scrollView.addView(mainLayout)
        setContentView(scrollView)

        // Title
        val titleText = TextView(this).apply {
            text = "App Management"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }
        mainLayout.addView(titleText)

        // Search bar
        val searchLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        val searchEditText = EditText(this).apply {
            hint = "Search apps..."
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val searchButton = Button(this).apply {
            text = "Search"
            setOnClickListener {
                filterApps(searchEditText.text.toString())
            }
        }

        searchLayout.addView(searchEditText)
        searchLayout.addView(searchButton)
        mainLayout.addView(searchLayout)

        // App list
        val listView = ListView(this)
        installedAppsAdapter = AppListAdapter()
        listView.adapter = installedAppsAdapter
        mainLayout.addView(listView)

        // Load apps
        loadInstalledApps()

        // Action buttons
        val buttonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }

        val refreshButton = Button(this).apply {
            text = "Refresh"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                loadInstalledApps()
            }
        }

        val backButton = Button(this).apply {
            text = "Back"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                finish()
            }
        }

        buttonsLayout.addView(refreshButton)
        buttonsLayout.addView(backButton)
        mainLayout.addView(buttonsLayout)
    }

    private fun loadInstalledApps() {
        try {
            val apps = mutableListOf<AppInfo>()

            val intent = Intent(Intent.ACTION_MAIN, null)
            intent.addCategory(Intent.CATEGORY_LAUNCHER)

            val resolveInfoList = packageManager.queryIntentActivities(intent, 0)

            for (resolveInfo in resolveInfoList) {
                val appInfo = resolveInfo.activityInfo.applicationInfo
                if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0) { // Non-system apps only
                    val appName = appInfo.loadLabel(packageManager).toString()
                    val packageName = appInfo.packageName
                    val icon = appInfo.loadIcon(packageManager)

                    apps.add(AppInfo(appName, packageName, icon, false))
                }
            }

            // Sort by name
            apps.sortBy { it.name }

            installedApps.clear()
            installedApps.addAll(apps)
            installedAppsAdapter.notifyDataSetChanged()

            Log.d("AppManagement", "Loaded ${installedApps.size} user apps")

        } catch (e: Exception) {
            Log.e("AppManagement", "Error loading apps", e)
            Toast.makeText(this, "Error loading apps", Toast.LENGTH_SHORT).show()
        }
    }

    private fun filterApps(query: String) {
        val filteredApps = if (query.isEmpty()) {
            installedApps
        } else {
            installedApps.filter { it.name.contains(query, true) || it.packageName.contains(query, true) }
        }
        installedAppsAdapter.updateApps(filteredApps)
    }

    private inner class AppListAdapter : BaseAdapter() {

        private var displayApps = mutableListOf<AppInfo>()

        init {
            displayApps.addAll(installedApps)
        }

        fun updateApps(newApps: List<AppInfo>) {
            displayApps.clear()
            displayApps.addAll(newApps)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = displayApps.size

        override fun getItem(position: Int): AppInfo = displayApps[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
            val view = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)

            val app = getItem(position)

            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)

            text1.text = app.name
            text2.text = app.packageName

            return view
        }
    }

    data class AppInfo(
        val name: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable,
        val isSystemApp: Boolean
    )
}

