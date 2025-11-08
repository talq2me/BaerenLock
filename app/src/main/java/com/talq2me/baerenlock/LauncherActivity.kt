package com.talq2me.baerenlock

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.talq2me.contract.SettingsContract

class LauncherActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var rewardRunnable: Runnable? = null
    private lateinit var appGrid: GridLayout
    private var accessibilityBanner: Button? = null
    private lateinit var prefs: SharedPreferences
    private var rewardMinutesTextView: TextView? = null

    companion object {
        private const val TAG = "LauncherActivity"
        const val ACTION_REWARD_EXPIRED = "com.talq2me.baerenlock.ACTION_REWARD_EXPIRED"
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

        prefs = getSharedPreferences("com.talq2me.baerenlock.prefs", Context.MODE_PRIVATE)

        val userProfile = readProfile()

        val background = createDailyBackgroundImageView(userProfile)

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        val root = FrameLayout(this).apply {
            addView(background)
            addView(contentLayout)
        }

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

        setContentView(root)

        if (userProfile == null) {
            getOrCreateProfile()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        RewardManager.loadAllowedApps(this)
        RewardManager.loadRewardMinutes(this)

        val incomingRewardMinutes = intent.getIntExtra("reward_minutes", 0)
        if (incomingRewardMinutes > 0) {
            RewardManager.currentRewardMinutes += incomingRewardMinutes
            RewardManager.saveRewardMinutes(this)
            intent.removeExtra("reward_minutes")
            updateRewardMinutesDisplay()
        }

        refreshIcons(appGrid)
        startRewardDisplayUpdate()
    }

    override fun onResume() {
        super.onResume()
        refreshIcons(appGrid)
        updateAccessibilityBanner(appGrid.parent as ViewGroup)

        RewardManager.loadRewardMinutes(this)
        if (RewardManager.currentRewardMinutes > 0) {
            RewardManager.startRewardTimer(this)
        }

        startRewardDisplayUpdate()

        LocalBroadcastManager.getInstance(this).registerReceiver(
            rewardExpiredReceiver, IntentFilter(ACTION_REWARD_EXPIRED)
        )
    }

    override fun onPause() {
        super.onPause()
        stopRewardDisplayUpdate()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(rewardExpiredReceiver)
    }

    private fun startRewardDisplayUpdate() {
        rewardRunnable?.let { handler.removeCallbacks(it) }
        rewardRunnable = object : Runnable {
            override fun run() {
                updateRewardMinutesDisplay()
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(rewardRunnable!!)
    }

    private fun stopRewardDisplayUpdate() {
        rewardRunnable?.let { handler.removeCallbacks(it) }
        rewardRunnable = null
    }

    private fun updateRewardMinutesDisplay() {
        val minutes = RewardManager.currentRewardMinutes
        runOnUiThread {
            rewardMinutesTextView?.text = "Reward: $minutes min"
            refreshIcons(appGrid)
        }
    }

    private fun readProfile(): String? {
        try {
            contentResolver.query(SettingsContract.CONTENT_URI, arrayOf(SettingsContract.KEY_PROFILE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(cursor.getColumnIndexOrThrow(SettingsContract.KEY_PROFILE))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read profile from provider.", e)
        }
        return null
    }

    private fun writeProfile(newProfile: String) {
        val values = ContentValues().apply {
            put(SettingsContract.KEY_PROFILE, newProfile)
        }
        try {
            contentResolver.update(SettingsContract.CONTENT_URI, values, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write profile to provider.", e)
        }
    }

    private fun getOrCreateProfile(): String? {
        readProfile()?.let { return it }

        val profiles = arrayOf("Profile A", "Profile B")
        AlertDialog.Builder(this)
            .setTitle("Select User Profile")
            .setCancelable(false)
            .setItems(profiles) { _, which ->
                val selectedProfile = if (which == 0) "A" else "B"
                writeProfile(selectedProfile)
                finishAffinity()
                startActivity(Intent(this, LauncherActivity::class.java))
            }
            .show()
        return null
    }

    private fun readPin(): String? {
        try {
            contentResolver.query(SettingsContract.CONTENT_URI, arrayOf(SettingsContract.KEY_PIN), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(cursor.getColumnIndexOrThrow(SettingsContract.KEY_PIN))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read PIN from provider.", e)
        }
        return null
    }

    private fun writePin(newPin: String) {
        val values = ContentValues().apply {
            put(SettingsContract.KEY_PIN, newPin)
        }
        try {
            contentResolver.update(SettingsContract.CONTENT_URI, values, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write PIN to provider.", e)
        }
    }

    private fun readEmail(): String? {
        try {
            contentResolver.query(SettingsContract.CONTENT_URI, arrayOf(SettingsContract.KEY_PARENT_EMAIL), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(cursor.getColumnIndexOrThrow(SettingsContract.KEY_PARENT_EMAIL))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read email from provider.", e)
        }
        return null
    }

    private fun writeEmail(newEmail: String) {
        val values = ContentValues().apply {
            put(SettingsContract.KEY_PARENT_EMAIL, newEmail)
        }
        try {
            contentResolver.update(SettingsContract.CONTENT_URI, values, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write email to provider.", e)
        }
    }

    private fun refreshIcons(container: ViewGroup) {
        container.removeAllViews()
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val allowedApps = apps.filter { RewardManager.isAllowed(it.activityInfo.packageName) }

        if (container is GridLayout) {
            val displayMetrics = resources.displayMetrics
            container.columnCount = if (displayMetrics.widthPixels > displayMetrics.heightPixels) 8 else 5
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
                    packageManager.getLaunchIntentForPackage(ri.activityInfo.packageName)?.let { startActivity(it) }
                }
            }
            val label = TextView(this).apply {
                text = ri.loadLabel(pm)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 10f
                maxLines = 1
            }
            appLayout.addView(icon)
            appLayout.addView(label)
            container.addView(appLayout)
        }
    }

    private fun showPinPrompt(onSuccess: () -> Unit) {
        val storedPin = readPin() ?: "1234"
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 50; rightMargin = 50
            }
        }
        val container = FrameLayout(this)
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Enter PIN")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == storedPin) onSuccess() else Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${AppBlockerService::class.java.name}"
        val enabledServices = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.split(':')?.any { it.equals(expected, ignoreCase = true) } ?: false
    }

    private fun showSettingsMenu() {
        val options = arrayOf("App Whitelist", "Reward Apps", "Change PIN", "Change Profile", "Change Parent Email")
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, WhitelistSettingsActivity::class.java))
                    1 -> startActivity(Intent(this, RewardAppsSettingsActivity::class.java))
                    2 -> showChangePinDialog()
                    3 -> showChangeProfileDialog()
                    4 -> showChangeEmailDialog()
                }
            }
            .show()
    }

    private fun showChangeProfileDialog() {
        val profiles = arrayOf("Profile A", "Profile B")
        val currentProfile = readProfile()
        AlertDialog.Builder(this)
            .setTitle("Select User Profile")
            .setItems(profiles) { _, which ->
                val selectedProfile = if (which == 0) "A" else "B"
                if (currentProfile != selectedProfile) {
                    writeProfile(selectedProfile)
                    finishAffinity()
                    startActivity(Intent(this, LauncherActivity::class.java))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChangePinDialog() {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val newPinInput = EditText(this).apply {
            hint = "Enter new PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        dialogLayout.addView(newPinInput)

        val confirmPinInput = EditText(this).apply {
            hint = "Confirm new PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        dialogLayout.addView(confirmPinInput)

        AlertDialog.Builder(this)
            .setTitle("Change PIN")
            .setView(dialogLayout)
            .setPositiveButton("Save") { _, _ ->
                val newPin = newPinInput.text.toString()
                val confirmPin = confirmPinInput.text.toString()

                if (newPin.isNotEmpty() && newPin == confirmPin) {
                    writePin(newPin)
                    Toast.makeText(this, "PIN changed successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PINs do not match or are empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChangeEmailDialog() {
        val currentEmail = readEmail() ?: ""
        val input = EditText(this).apply {
            hint = "Parent Email Address"
            setText(currentEmail)
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        val container = FrameLayout(this).apply {
            setPadding(50, 20, 50, 20)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Change Parent Email")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newEmail = input.text.toString().trim()
                if (newEmail.isNotEmpty() && android.util.Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) {
                    writeEmail(newEmail)
                    Toast.makeText(this, "Email saved successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createDailyBackgroundImageView(userProfile: String?): ImageView {
        return ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP

            if (userProfile == null) {
                setBackgroundColor(Color.GRAY)
                return@apply
            }

            val prefix = if (userProfile == "A") "bg_a_" else "bg_b_"
            val fields = R.drawable::class.java.fields
            val drawables = fields.filter { it.name.startsWith(prefix) }

            if (drawables.isNotEmpty()) {
                val randomDrawableId = drawables.random().getInt(null)
                setImageResource(randomDrawableId)
            } else {
                // Fallback to a solid color if no matching images are found
                setBackgroundColor(if (userProfile == "A") Color.BLUE else Color.DKGRAY)
            }
        }
    }

    private fun exitLauncher() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        // Create and start a chooser, forcing the user to select a launcher.
        val chooser = Intent.createChooser(homeIntent, "Select Home App")
        startActivity(chooser)
    }

    private fun updateAccessibilityBanner(container: ViewGroup) {
        accessibilityBanner?.visibility = if (isAccessibilityServiceEnabled()) View.GONE else View.VISIBLE
    }
}
