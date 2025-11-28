package com.talq2me.baerenlock

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
    private var backgroundImageView: ImageView? = null

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

        backgroundImageView = createDailyBackgroundImageView(userProfile)

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        val root = FrameLayout(this).apply {
            addView(backgroundImageView)
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
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
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

        val versionTextView = TextView(this).apply {
            text = getVersionLabel()
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        topBar.addView(versionTextView)

        rewardMinutesTextView = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
            gravity = Gravity.CENTER_VERTICAL
            text = "Reward: 0 min"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
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
        
        // Check if we're the default launcher (check on resume in case user changed it)
        ensureDefaultLauncher()
        
        // Check for updates when coming to foreground (throttled to once per hour)
        MainActivity.checkForUpdate(this)
        
        // Refresh background image in case it was cleared from memory
        refreshBackgroundImage()
        
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

    private fun ensureDefaultLauncher() {
        val devicePolicyManager = DevicePolicyManager.getInstance(this)
        if (devicePolicyManager.isDeviceOwnerActive()) {
            try {
                val componentName = ComponentName(this, LauncherActivity::class.java)
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
                
                // Set this app as the default launcher (requires device owner)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.addPersistentPreferredActivity(
                        adminComponent,
                        IntentFilter(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            addCategory(Intent.CATEGORY_DEFAULT)
                        },
                        componentName
                    )
                    Log.d(TAG, "Set BaerenLock as default launcher (device owner)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set default launcher: ${e.message}", e)
            }
        } else {
            // If not device owner, check if we're the default launcher using a more reliable method
            val isDefaultLauncher = isDefaultLauncher()
            
            if (!isDefaultLauncher) {
                Log.d(TAG, "Not set as default launcher")
                // Show prompt (but allow it to be shown again if user dismisses and comes back)
                val prefs = getSharedPreferences("com.talq2me.baerenlock.prefs", Context.MODE_PRIVATE)
                val lastPromptTime = prefs.getLong("launcher_prompt_last_shown", 0)
                val currentTime = System.currentTimeMillis()
                // Show prompt if never shown, or if last shown more than 1 hour ago
                if (lastPromptTime == 0L || (currentTime - lastPromptTime) > 3600000) {
                    AlertDialog.Builder(this)
                        .setTitle("Set BaerenLock as Home")
                        .setMessage("BaerenLock needs to be set as your default launcher to work properly. When you press the home button, it should open BaerenLock.\n\nPlease go to Settings and select BaerenLock as your Home app.")
                        .setPositiveButton("Open Settings") { _, _ ->
                            try {
                                // Try the direct home settings intent first
                                val settingsIntent = Intent(Settings.ACTION_HOME_SETTINGS)
                                startActivity(settingsIntent)
                            } catch (e: Exception) {
                                // Fallback to general app settings
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.parse("package:$packageName")
                                    }
                                    startActivity(intent)
                                } catch (e2: Exception) {
                                    Log.e(TAG, "Failed to open settings", e2)
                                }
                            }
                        }
                        .setNegativeButton("Later", null)
                        .setCancelable(false)
                        .show()
                    prefs.edit().putLong("launcher_prompt_last_shown", currentTime).apply()
                }
            } else {
                Log.d(TAG, "BaerenLock is set as default launcher")
                // Clear the prompt flag if we're now the default
                val prefs = getSharedPreferences("com.talq2me.baerenlock.prefs", Context.MODE_PRIVATE)
                prefs.edit().putLong("launcher_prompt_last_shown", 0).apply()
            }
        }
    }
    
    private fun isDefaultLauncher(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            
            // Resolve the HOME intent to see which launcher is currently default
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            
            if (resolveInfo != null) {
                val defaultPackage = resolveInfo.activityInfo.packageName
                val isDefault = defaultPackage == packageName
                Log.d(TAG, "Default launcher check: current=$defaultPackage, ourPackage=$packageName, isDefault=$isDefault")
                return isDefault
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking default launcher: ${e.message}", e)
            false
        }
    }

    private fun getVersionLabel(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val name = packageInfo.versionName ?: packageInfo.longVersionCode.toString()
            "v$name"
        } catch (e: Exception) {
            "v?"
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
                    val pkgName = ri.activityInfo.packageName
                    // If clicking on BaerenLock itself, force an update check
                    if (pkgName == packageName) {
                        Log.d(TAG, "BaerenLock icon clicked - forcing update check")
                        MainActivity.checkForUpdate(this@LauncherActivity, force = true)
                    }
                    // Launch the app normally (or do nothing if it's BaerenLock since we're already in it)
                    if (pkgName != packageName) {
                        packageManager.getLaunchIntentForPackage(pkgName)?.let { startActivity(it) }
                    }
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
        val options = arrayOf("App Whitelist", "Reward Apps", "Blocked Apps", "Change PIN", "Change Profile", "Change Parent Email")
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, WhitelistSettingsActivity::class.java))
                    1 -> startActivity(Intent(this, RewardAppsSettingsActivity::class.java))
                    2 -> startActivity(Intent(this, BlackListSettingsActivity::class.java))
                    3 -> showChangePinDialog()
                    4 -> showChangeProfileDialog()
                    5 -> showChangeEmailDialog()
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
            // Set dark grey as fallback background color (will show if image fails to load)
            setBackgroundColor(Color.parseColor("#2D2D2D")) // Dark grey

            if (userProfile == null) {
                setBackgroundColor(Color.parseColor("#2D2D2D"))
                return@apply
            }

            val prefix = if (userProfile == "A") "bg_a_" else "bg_b_"
            val fields = R.drawable::class.java.fields
            // Only use the _orig.jpg files, not the XML files
            val drawables = fields.filter { 
                it.name.startsWith(prefix) && it.name.endsWith("_orig")
            }

            if (drawables.isNotEmpty()) {
                try {
                    val randomDrawableId = drawables.random().getInt(null)
                    setImageResource(randomDrawableId)
                    Log.d(TAG, "Set background image: ${drawables.find { it.getInt(null) == randomDrawableId }?.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set background image: ${e.message}", e)
                    // Fallback to dark grey if image loading fails
                    setBackgroundColor(Color.parseColor("#2D2D2D"))
                }
            } else {
                // Fallback to dark grey if no matching images are found
                Log.w(TAG, "No background images found for profile: $userProfile")
                setBackgroundColor(Color.parseColor("#2D2D2D"))
            }
        }
    }

    private fun refreshBackgroundImage() {
        val userProfile = readProfile()
        Log.d(TAG, "refreshBackgroundImage called, userProfile: $userProfile, backgroundImageView is null: ${backgroundImageView == null}")
        
        if (backgroundImageView == null) {
            Log.w(TAG, "backgroundImageView is null, cannot refresh")
            return
        }
        
        val imageView = backgroundImageView!!
        // Always ensure dark grey background is set as fallback
        imageView.setBackgroundColor(Color.parseColor("#2D2D2D"))
        
        if (userProfile == null) {
            Log.d(TAG, "No user profile, using dark grey background")
            return
        }

        // Always reload the background image when returning to launcher
        // This ensures it's displayed even if it was cleared from memory
        Log.d(TAG, "Refreshing background image for profile: $userProfile")
        
        val prefix = if (userProfile == "A") "bg_a_" else "bg_b_"
        val fields = R.drawable::class.java.fields
        // Only use the _orig.jpg files, not the XML files
        val drawables = fields.filter { 
            it.name.startsWith(prefix) && it.name.endsWith("_orig")
        }

        Log.d(TAG, "Found ${drawables.size} drawables with prefix: $prefix (filtered for _orig files)")

        if (drawables.isNotEmpty()) {
            try {
                val randomDrawableId = drawables.random().getInt(null)
                val drawableName = drawables.find { it.getInt(null) == randomDrawableId }?.name
                Log.d(TAG, "Setting background image resource: $drawableName (id: $randomDrawableId)")
                
                // Clear any existing image first to force reload
                imageView.setImageDrawable(null)
                
                // Set the new image
                imageView.setImageResource(randomDrawableId)
                
                // Force a layout update
                imageView.invalidate()
                imageView.requestLayout()
                
                Log.d(TAG, "Successfully refreshed background image: $drawableName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh background image: ${e.message}", e)
                e.printStackTrace()
                // Image will fail to load, dark grey background will show through
                imageView.setImageDrawable(null)
            }
        } else {
            // No images found, ensure image is cleared so background shows
            Log.w(TAG, "No background images found for profile: $userProfile")
            imageView.setImageDrawable(null)
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
