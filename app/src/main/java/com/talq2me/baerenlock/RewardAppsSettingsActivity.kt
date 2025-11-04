package com.talq2me.baerenlock

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback

class RewardAppsSettingsActivity : AppCompatActivity() {
    private lateinit var appsListView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val appLabels = mutableListOf<String>()
    private val appPackages = mutableListOf<String>()
    private val selectedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set up the OnBackPressedCallback
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                prefs.edit().putStringSet("reward_apps", selectedPackages).apply()
                Toast.makeText(this@RewardAppsSettingsActivity, "Reward apps saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        })
        
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(context).apply {
                text = "Select Reward Apps"
                textSize = 22f
                setPadding(0, 0, 0, 24)
            })
            appsListView = ListView(context)
            addView(appsListView)
        })
        loadApps()
    }

    private fun loadApps() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val saved = prefs.getStringSet("reward_apps", emptySet()) ?: emptySet()
        appLabels.clear()
        appPackages.clear()
        selectedPackages.clear()
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString()
            val pkg = ri.activityInfo.packageName
            appLabels.add(label)
            appPackages.add(pkg)
            if (pkg in saved) selectedPackages.add(pkg)
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
    }
} 
