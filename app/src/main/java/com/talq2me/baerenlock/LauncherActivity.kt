package com.talq2me.baerenlock

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.activity.OnBackPressedCallback
import androidx.localbroadcastmanager.content.LocalBroadcastManager // Import LocalBroadcastManager
import android.content.BroadcastReceiver
import android.content.IntentFilter // Import IntentFilter
import java.util.Calendar
import android.content.Context
import android.content.SharedPreferences

class LauncherActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var rewardRunnable: Runnable? = null
    private lateinit var appGrid: GridLayout
    private var accessibilityBanner: Button? = null
    private lateinit var prefs: SharedPreferences
    private var rewardMinutesTextView: TextView? = null

    companion object {
        private const val PROFILE_KEY = "user_profile"
        private const val TAG = "LauncherActivity"
        private const val REWARD_UPDATE_INTERVAL = 5 * 60 * 1000L // 5 minutes in milliseconds
        const val ACTION_REWARD_EXPIRED = "com.talq2me.baerenlock.ACTION_REWARD_EXPIRED" // Define custom action
    }

    private val rewardExpiredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REWARD_EXPIRED) {
                Log.d(TAG, "Received ACTION_REWARD_EXPIRED broadcast. Refreshing UI.")
                updateRewardMinutesDisplay()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("settings", MODE_PRIVATE)

        // Determine or create user profile on first launch
        val userProfile = getOrCreateProfile()
        Log.d(TAG, "Current user profile: $userProfile")

        // --- Start UI Initialization (moved up) ---
        val background = createDailyBackgroundImageView(userProfile)

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        val root = FrameLayout(this).apply {
            addView(background)
            addView(contentLayout)
        }

        // Accessibility banner placeholder
        accessibilityBanner = Button(this).apply {
            text = "Enable Protection (Accessibility)"
            setBackgroundColor(0xFFE57373.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }
        contentLayout.addView(accessibilityBanner, 0)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(80, 80)
            setOnClickListener {
                showPinPrompt(onSuccess = {
                    showSettingsMenu()
                })
            }
        }
        topBar.addView(settingsButton)

        rewardMinutesTextView = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
            gravity = Gravity.CENTER_VERTICAL
            text = "Reward: 0 min"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1.0f
                gravity = Gravity.START
            }
        }
        topBar.addView(rewardMinutesTextView)

        val breakGlassButton = ImageButton(this).apply {
            setImageResource(R.drawable.exit_launcher)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(80, 80)
            setOnClickListener {
                showPinPrompt(onSuccess = {
                    exitLauncher()
                })
            }
        }
        topBar.addView(breakGlassButton)

        val webAppButton = Button(this).apply {
            text = "Baeren"
            setOnClickListener {
                startActivity(Intent(this@LauncherActivity, MainActivity::class.java))
            }
        }

        appGrid = GridLayout(this).apply {
            val displayMetrics = resources.displayMetrics
            val isLandscape = displayMetrics.widthPixels > displayMetrics.heightPixels
            columnCount = if (isLandscape) 8 else 5
            useDefaultMargins = false
        }

        contentLayout.addView(topBar)
        contentLayout.addView(webAppButton)
        contentLayout.addView(appGrid)

        setContentView(root) // Set content view after all essential UI is added

        // --- End UI Initialization ---


        // Set up the OnBackPressedCallback (can stay here)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing to disable the back button in the launcher activity
            }
        })

        RewardManager.loadAllowedApps(this)
        RewardManager.loadRewardMinutes(this) // Load reward minutes on launch

        // Check for reward minutes passed from BaerenEd
        val incomingRewardMinutes = intent.getIntExtra("reward_minutes", 0)
        if (incomingRewardMinutes > 0) {
            RewardManager.currentRewardMinutes += incomingRewardMinutes
            RewardManager.saveRewardMinutes(this)
            Log.d(TAG, "After saving, RewardManager.currentRewardMinutes is: ${RewardManager.currentRewardMinutes}") // New log
            Log.d(TAG, "Received $incomingRewardMinutes reward minutes from Intent. Total: ${RewardManager.currentRewardMinutes}")
            // Consume the intent extra so it's not processed again on recreate
            intent.removeExtra("reward_minutes")
            updateRewardMinutesDisplay() // This call should now be safe
        }

        // The remaining calls for refreshIcons and startRewardDisplayUpdate are safe here
        refreshIcons(appGrid)
        startRewardDisplayUpdate()
    }

    override fun onResume() {
        super.onResume()
        // Refresh the app grid when returning from settings
        refreshIcons(appGrid)
        // Get the content layout properly - it's the parent of appGrid
        val contentLayout = appGrid.parent as? LinearLayout
        contentLayout?.let { updateAccessibilityBanner(it) }

        // Load reward minutes and start the timer when activity resumes/becomes active
        RewardManager.loadRewardMinutes(this)
        if (RewardManager.currentRewardMinutes > 0) {
            Log.d(TAG, "onResume: Reward minutes present (${RewardManager.currentRewardMinutes} min). Starting RewardManager timer.")
            RewardManager.startRewardTimer(this)
        } else {
            Log.d(TAG, "onResume: No reward minutes present. Not starting RewardManager timer.")
        }

        startRewardDisplayUpdate() // Start or restart the update when activity resumes

        // Register the BroadcastReceiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            rewardExpiredReceiver, IntentFilter(ACTION_REWARD_EXPIRED)
        )
    }

    override fun onPause() {
        super.onPause()
        stopRewardDisplayUpdate() // Stop the update when activity pauses

        // Unregister the BroadcastReceiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(rewardExpiredReceiver)
    }

    private fun startRewardDisplayUpdate() {
        rewardRunnable?.let { handler.removeCallbacks(it) }
        rewardRunnable = object : Runnable {
            override fun run() {
                updateRewardMinutesDisplay()
                handler.postDelayed(this, 1000L) // Update every second
            }
        }
        handler.post(rewardRunnable!!)
        Log.d(TAG, "Reward display update started.")
    }

    private fun stopRewardDisplayUpdate() {
        rewardRunnable?.let { handler.removeCallbacks(it) }
        rewardRunnable = null
        Log.d(TAG, "Reward display update stopped.")
    }

    private fun updateRewardMinutesDisplay() {
        val minutes = RewardManager.currentRewardMinutes
        Log.d(TAG, "Updating reward minutes display to: $minutes") // Add logging here
        runOnUiThread {
            rewardMinutesTextView?.text = "Reward: $minutes min"
            refreshIcons(appGrid) // Refresh icons to show/hide reward apps
        }
    }

    private fun getOrCreateProfile(): String {
        var profile = prefs.getString(PROFILE_KEY, null)
        if (profile == null) {
            // First launch, prompt user for profile selection
            val profiles = arrayOf("Profile A", "Profile B")
            AlertDialog.Builder(this)
                .setTitle("Select User Profile")
                .setCancelable(false) // Must choose a profile
                .setItems(profiles) { dialog, which ->
                    profile = when (which) {
                        0 -> "A"
                        1 -> "B"
                        else -> "A" // Default to A
                    }
                    prefs.edit().putString(PROFILE_KEY, profile).apply()
                    Log.d(TAG, "Selected new profile: $profile")
                    // Recreate activity to apply new background
                    recreate()
                }
                .show()
            // Return a default for now, recreate() will handle the actual application
            return "A"
        }
        return profile!!
    }

    private fun refreshIcons(container: ViewGroup) {
        container.removeAllViews()
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        )

        val allowedApps = apps.filter { info ->
            val packageName = info.activityInfo.packageName
            RewardManager.isAllowed(packageName) // Use RewardManager.isAllowed to determine visibility
        }

        // Update grid dimensions based on current orientation
        val displayMetrics = resources.displayMetrics
        val isLandscape = displayMetrics.widthPixels > displayMetrics.heightPixels
        val columns = if (isLandscape) 8 else 5
        
        // Update the grid's column count
        if (container is GridLayout) {
            container.columnCount = columns
        }
        
        allowedApps.forEach { ri ->
            val appLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 150
                    height = 180
                    setMargins(4, 4, 4, 4)
                }
            }

            val icon = ImageView(this).apply {
                setImageDrawable(ri.loadIcon(pm))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LinearLayout.LayoutParams(120, 120)
                setOnClickListener {
                    if (ri.activityInfo.packageName == packageName) {
                        val intent = Intent(this@LauncherActivity, MainActivity::class.java)
                        startActivity(intent)
                    } else {
                        val intent = packageManager.getLaunchIntentForPackage(ri.activityInfo.packageName)
                        if (intent != null) startActivity(intent)
                    }
                }
            }

            val label = TextView(this).apply {
                text = ri.loadLabel(pm).toString()
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 10f
                maxLines = 1
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            appLayout.addView(icon)
            appLayout.addView(label)
            container.addView(appLayout)
        }
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) startActivity(intent)
        else Toast.makeText(this, "App not installed", Toast.LENGTH_SHORT).show()
    }

    private fun bringHome() {
        val home = Intent(this, LauncherActivity::class.java)
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(home)
    }

    private fun showPinPrompt(onSuccess: () -> Unit) {
        val storedPin = prefs.getString("parent_pin", "1234") ?: "1234"
        PinPromptDialog.show(this, "Enter PIN") { enteredPin ->
            if (enteredPin == storedPin) {
                onSuccess()
            } else {
                Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                showPinPrompt(onSuccess)
            } 
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${AppBlockerService::class.java.name}"
        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        // Check for both flattened and unflattened names
        return enabledServices.split(':').any {
            it.equals(expected, ignoreCase = true) ||
            it.endsWith(AppBlockerService::class.java.name, ignoreCase = true)
        }
    }

    private fun showSettingsMenu() {
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(arrayOf("App Whitelist", "Reward Apps", "Parent Email Settings", "Change PIN")) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this@LauncherActivity, WhitelistSettingsActivity::class.java))
                    1 -> startActivity(Intent(this@LauncherActivity, RewardAppsSettingsActivity::class.java))
                    2 -> startActivity(Intent(this@LauncherActivity, SettingsActivity::class.java))
                    3 -> startActivity(Intent(this@LauncherActivity, ChangePinActivity::class.java))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exitLauncher() {
        val intent = Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)

        Toast.makeText(this, "Choose a different default launcher in settings.", Toast.LENGTH_LONG).show()
    }

    private fun createDailyBackgroundImageView(userProfile: String): ImageView {
        val backgrounds = when (userProfile) {
            "A" -> listOf(R.drawable.bg_a_1_orig, R.drawable.bg_a_2_orig, R.drawable.bg_a_3_orig)
            "B" -> listOf(R.drawable.bg_b_1_orig, R.drawable.bg_b_2_orig, R.drawable.bg_b_3_orig)
            else -> listOf(R.drawable.bg_a_1_orig, R.drawable.bg_a_2_orig, R.drawable.bg_a_3_orig) // Default to A
        }

        val dayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val index = dayOfYear % backgrounds.size

        val bgRes = backgrounds[index]

        return ImageView(this).apply {
            setImageResource(bgRes)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun updateAccessibilityBanner(contentLayout: LinearLayout) {
        val isEnabled = isAccessibilityServiceEnabled()
        accessibilityBanner?.visibility = if (isEnabled) View.GONE else View.VISIBLE
    }
}
