package com.talq2me.baerenlock

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RewardAppsSettingsActivity : AppCompatActivity() {
    private lateinit var appsListView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val appLabels = mutableListOf<String>()
    private val appPackages = mutableListOf<String>()
    private val selectedPackages = mutableSetOf<String>()
    private var initialPackages = emptySet<String>()
    private var loaded = false
    private var loadingView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { saveAndFinish() }
        })
        val loadingTv = TextView(this).apply {
            text = "Loading from database..."
            setPadding(0, 0, 0, 24)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(loadingTv)
            appsListView = ListView(context)
            addView(appsListView)
        })
        loadingView = loadingTv
        lifecycleScope.launch {
            val profile = ProfileManager.getCurrentProfile(this@RewardAppsSettingsActivity)
            val fromCloud = withContext(Dispatchers.IO) { CloudSyncManager.fetchAppListsFromCloud(this@RewardAppsSettingsActivity, profile) }
            val rewardFromDb = fromCloud?.rewardApps ?: emptySet()
            initialPackages = rewardFromDb
            selectedPackages.clear()
            selectedPackages.addAll(rewardFromDb)
            withContext(Dispatchers.Main) { loadAppsUi() }
        }
    }

    private fun loadAppsUi() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
        appLabels.clear()
        appPackages.clear()
        for (ri in apps) {
            appLabels.add(ri.loadLabel(pm).toString())
            appPackages.add(ri.activityInfo.packageName)
        }
        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice, appLabels) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent)
                val icon = pm.getApplicationIcon(appPackages[position])
                (view as CheckedTextView).setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
                view.compoundDrawablePadding = 24
                return view
            }
        }
        appsListView.adapter = adapter
        appsListView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        for (i in appPackages.indices) {
            appsListView.setItemChecked(i, appPackages[i] in selectedPackages)
        }
        appsListView.setOnItemClickListener { _, _, pos, _ ->
            val pkg = appPackages[pos]
            if (selectedPackages.contains(pkg)) selectedPackages.remove(pkg) else selectedPackages.add(pkg)
        }
        (loadingView?.parent as? android.view.ViewGroup)?.removeView(loadingView)
        loadingView = null
        loaded = true
    }

    private fun saveAndFinish() {
        if (!loaded) { finish(); return }
        if (selectedPackages == initialPackages) { finish(); return }
        lifecycleScope.launch {
            val profile = ProfileManager.getCurrentProfile(this@RewardAppsSettingsActivity)
            val json = com.google.gson.Gson().toJson(selectedPackages.toList())
            val ok = withContext(Dispatchers.IO) {
                CloudSyncManager.patchAppListToCloud(this@RewardAppsSettingsActivity, profile, "reward_apps", json)
            }
            withContext(Dispatchers.Main) {
                if (ok) Toast.makeText(this@RewardAppsSettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this@RewardAppsSettingsActivity, "Save failed", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
} 
