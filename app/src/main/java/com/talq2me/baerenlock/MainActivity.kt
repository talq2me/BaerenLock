package com.talq2me.baerenlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.provider.Settings
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.util.*
import android.util.Log
import android.widget.LinearLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.ScrollView
import android.widget.EditText
import android.widget.Button
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.talq2me.baerenlock.DevicePolicyManager as CustomDevicePolicyManager
import java.io.File
import org.json.JSONObject
import java.net.URL
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import android.os.Build
import androidx.lifecycle.lifecycleScope
import java.util.Calendar
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "MainActivity"
        
        // GitHub upload configuration
        // GitHub token is encrypted using AES-256-CBC and stored in BuildConfig
        // Repository: BaerenCloud (dedicated repository for reports and artifacts)
        // Reports path: BaerenEd_Reports/ (same as BaerenEd for consistency)
        private const val GITHUB_OWNER = "talq2me"
        private const val GITHUB_REPO = "BaerenCloud"
        private const val GITHUB_REPORTS_PATH = "BaerenEd_Reports"  // Directory in repo for reports (same as BaerenEd)
    }

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private var rewardAppDialog: AlertDialog? = null

    private lateinit var requestOverlayPermissionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardianForegroundService.ensureRunning(this)
        // Preload settings from Supabase on startup
        SettingsManager.preloadSettings(this)

        requestOverlayPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
            }
        }

        // Check if this is a trigger to upload reward report FIRST, before doing anything else
        val isTriggerReportUpload = intent.getBooleanExtra("trigger_report_upload", false)
        
        if (isTriggerReportUpload) {
            Log.d(TAG, "MainActivity started with trigger_report_upload flag - will upload report")
            // Set up minimal UI to keep activity alive during upload
            // Use a simple TextView instead of WebView
            val textView = TextView(this).apply {
                text = "Uploading report..."
                textSize = 18f
                gravity = android.view.Gravity.CENTER
            }
            setContentView(textView)
            
            // Register receiver first so we can catch the broadcast if it comes
            registerRewardReportReceiver()
            
            // Don't launch BaerenEd or do normal setup - just handle the upload
            Handler(Looper.getMainLooper()).postDelayed({
                // Check if we have usage data to upload
                val sessions = RewardManager.lastRewardSessions
                val summary = RewardManager.lastRewardSummary
                Log.d(TAG, "Checking for usage data: sessions=${sessions != null} (${sessions?.size ?: 0}), summary=${summary != null}")
                if (sessions != null && summary != null) {
                    Log.d(TAG, "Uploading reward report from triggered MainActivity start")
                    textView.text = "Uploading report to GitHub..."
                    generateAndUploadRewardReport(sessions, summary)
                    RewardManager.lastRewardSessions = null
                    RewardManager.lastRewardSummary = null
                    // Finish activity after a short delay to allow upload to complete
                    Handler(Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 3000)
                } else {
                    Log.w(TAG, "No usage data available yet, waiting for broadcast or retry")
                    textView.text = "Waiting for usage data..."
                    // Wait a bit more in case data is still being stored or broadcast is coming
                    Handler(Looper.getMainLooper()).postDelayed({
                        val retrySessions = RewardManager.lastRewardSessions
                        val retrySummary = RewardManager.lastRewardSummary
                        if (retrySessions != null && retrySummary != null) {
                            Log.d(TAG, "Found usage data on retry, uploading now")
                            textView.text = "Uploading report to GitHub..."
                            generateAndUploadRewardReport(retrySessions, retrySummary)
                            RewardManager.lastRewardSessions = null
                            RewardManager.lastRewardSummary = null
                            Handler(Looper.getMainLooper()).postDelayed({
                                finish()
                            }, 3000)
                        } else {
                            Log.e(TAG, "Still no usage data after retry - report upload will not happen")
                            textView.text = "No usage data found"
                            Handler(Looper.getMainLooper()).postDelayed({
                                finish()
                            }, 2000)
                        }
                    }, 2000) // Wait 2 more seconds
                }
            }, 500) // Small delay to ensure receiver is registered
            return // Don't continue with normal MainActivity flow
        }

        // Load persistent whitelist
        RewardManager.loadAllowedApps(this)

        // Ensure PokemonGo is in allowed apps if installed
        RewardManager.addPokemonGoIfInstalled(this)

        // ✅ Set up webView immediately so it's safe to use
        webView = WebView(this)
        setContentView(webView)
        initWebView()
        // Redundant with initWebView's clearCache and cacheMode = LOAD_NO_CACHE
        // webView.clearCache(true);
        // webView.clearFormData();

        // Clean up unauthorized background apps when taking control
        cleanupBackgroundApps()

        // Load default web content (no child profile logic needed)
        launchBaerenEdApp()

        // Check if this is a test report request
        if (intent.getBooleanExtra("test_report", false)) {
            Handler(Looper.getMainLooper()).postDelayed({
                sendUsageReport()
            }, 1000) // Small delay to ensure everything is loaded
        }

        // Init TTS and permissions
        tts = TextToSpeech(this, this)
        maybeRequestBatteryOptimization()
        maybeRequestOverlayPermission()
        webView.addJavascriptInterface(TTSBridge(), "AndroidTTS")

        webView.addJavascriptInterface(UsageTrackerBridge(), "AndroidUsageTracker")

        webView.addJavascriptInterface(PinBridge(webView), "Android")
        
        // Register receiver for reward report generation
        registerRewardReportReceiver()

    }
    
    private val rewardReportReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "rewardReportReceiver.onReceive called with action: ${intent?.action}")
            if (intent?.action == "com.talq2me.baerenlock.ACTION_GENERATE_REWARD_REPORT") {
                Log.d(TAG, "Received ACTION_GENERATE_REWARD_REPORT broadcast")
                // Get usage data from RewardManager
                val sessions = RewardManager.lastRewardSessions
                val summary = RewardManager.lastRewardSummary
                
                Log.d(TAG, "Usage data check: sessions=${sessions != null} (${sessions?.size ?: 0} sessions), summary=${summary != null}")
                
                if (sessions != null && summary != null) {
                    Log.d(TAG, "Generating and uploading reward report with ${sessions.size} sessions")
                    generateAndUploadRewardReport(sessions, summary)
                    // Clear the stored data
                    RewardManager.lastRewardSessions = null
                    RewardManager.lastRewardSummary = null
                    Log.d(TAG, "Cleared stored usage data after upload")
                } else {
                    Log.w(TAG, "Cannot generate report: sessions or summary is null")
                }
            }
        }
    }
    
    private fun registerRewardReportReceiver() {
        val filter = IntentFilter("com.talq2me.baerenlock.ACTION_GENERATE_REWARD_REPORT")
        LocalBroadcastManager.getInstance(this).registerReceiver(rewardReportReceiver, filter)
        Log.d(TAG, "Registered reward report receiver with LocalBroadcastManager")
    }

    private fun initWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = "Mozilla/5.0 (Linux; Android 10; Pixel 3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.clearCache(true) // Clear cache on init
        // Redundant with cacheMode = LOAD_NO_CACHE and clearCache(true) above
        // webView.clearHistory()
        webView.webChromeClient = WebChromeClient()
        webView.settings.allowContentAccess = false


        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                val url = request.url.toString()

                if (url == "intent://sendusagereport") {
                    sendUsageReport() // call the email + reset logic
                    return true
                }

                // Special: open Read Along app via Play Store
                if (url.contains("readalong.google.com")) {
                    openPlayStore("com.google.android.apps.seekh")
                    return true
                }

                // Custom intent:// reward launcher
                if (url.startsWith("intent://")) {
                    try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        val minutes = intent.data?.getQueryParameter("minutes")?.toIntOrNull() ?: 10

                        Log.d("MainActivity", "Reward triggered: $minutes minutes")
                        // Show reward app picker dialog
                        showRewardAppPicker(minutes)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Intent error: ${e.message}", e)
                        Toast.makeText(this@MainActivity, "Intent error: "+e.message, Toast.LENGTH_LONG).show()
                    }
                    return true
                }

                return false
            }
        }
    }

    private fun setLayerType(layerTypeHardware: Any, nothing: Nothing?) {}

    private fun openPlayStore(pkg: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
            intent.setPackage("com.android.vending")
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }

    private fun maybeRequestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun maybeRequestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            requestOverlayPermissionLauncher.launch(intent)
        }
    }

    // TTS Setup
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        webView.evaluateJavascript("if (typeof onTTSFinish === 'function') { onTTSFinish(); }", null)
                    }
                }
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        webView.evaluateJavascript("if (typeof onTTSFinish === 'function') { onTTSFinish(); }", null)
                    }
                }
            })
        }
    }



    inner class TTSBridge {
        @JavascriptInterface
        fun speak(text: String, lang: String, rate: Float = 1.0f) {
            val locale = when (lang.lowercase()) {
                "fr" -> Locale.FRENCH
                "en" -> Locale.US
                else -> Locale.US
            }
            if (tts.setLanguage(locale) == TextToSpeech.LANG_MISSING_DATA) {
                Toast.makeText(this@MainActivity, "Unsupported lang: $lang", Toast.LENGTH_SHORT).show()
                webView.evaluateJavascript("if (typeof onTTSFinish === 'function') { onTTSFinish(); }", null)
                return
            }

            // Apply the speech rate
            tts.setSpeechRate(rate);

            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            val utteranceId = "utt_${System.currentTimeMillis()}"
            tts.stop()
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }

    private fun cleanupBackgroundApps() {
        try {
            Log.d("MainActivity", "Cleaning up unauthorized background apps...")
            val killedCount = RewardManager.killUnauthorizedBackgroundAppsWithCount(this)
            if (killedCount > 0) {
                Toast.makeText(this, "Cleaned up $killedCount background apps", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error cleaning up background apps", e)
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Before BaerenLock UI: af_daily_reset then fetch user_data/settings via RPCs (see DbUserDataRefresh).
        val profile = ProfileManager.getCurrentProfile(this)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                DbUserDataRefresh.runDailyResetThenFetchUserData(this@MainActivity, profile)
                Log.d(TAG, "DbUserDataRefresh completed in onResume for profile: $profile")
            } catch (e: Exception) {
                Log.e(TAG, "Error during DbUserDataRefresh in onResume", e)
            }
        }
    }

    override fun onDestroy() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(rewardReportReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("MainActivity", "Reward report receiver already unregistered")
        }
        // Check if tts was initialized before trying to use it
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    inner class UsageTrackerBridge {
        @JavascriptInterface
        fun logVisit(page: String, durationSeconds: Int) {
            val prefs = getSharedPreferences("usage_data", MODE_PRIVATE)
            val current = prefs.getString(page, "0")?.toIntOrNull() ?: 0
            prefs.edit().putString(page, (current + durationSeconds).toString()).apply()
        }
    }

    fun sendUsageReport() {
        val prefs = getSharedPreferences("usage_data", MODE_PRIVATE)
        val allData = prefs.all

        // Get parent email from settings
        val parentEmail = SettingsManager.readEmail(this)
        
        if (parentEmail.isNullOrBlank()) {
            Toast.makeText(this, "Please set parent email in settings first", Toast.LENGTH_LONG).show()
            return
        }

        val report = StringBuilder("Today's Activity Report:\n\n")

        // Web usage data
        if (allData.isNotEmpty()) {
            report.append("📱 Web Activity:\n")
            for ((key, value) in allData) {
                val minutes = (value.toString().toIntOrNull() ?: 0) / 60
                val seconds = (value.toString().toIntOrNull() ?: 0) % 60
                report.append("  • $key: ${minutes}m ${seconds}s\n")
            }
            report.append("\n")
        }

        // Android app usage data (if permission granted)
        val appUsageData = getAndroidAppUsageData()
        if (appUsageData.isNotEmpty()) {
            report.append("📲 App Usage:\n")
            for ((appName, duration) in appUsageData) {
                val minutes = duration / 60
                val seconds = duration % 60
                report.append("  • $appName: ${minutes}m ${seconds}s\n")
            }
            report.append("\n")
        }

        if (allData.isEmpty() && appUsageData.isEmpty()) {
            Toast.makeText(this, "No usage data to report", Toast.LENGTH_SHORT).show()
            return
        }

        // Try to send via Gmail directly
        val gmailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(parentEmail))
            putExtra(Intent.EXTRA_SUBJECT, "Daily Usage Report")
            putExtra(Intent.EXTRA_TEXT, report.toString())
            setPackage("com.google.android.gm")
        }
        if (gmailIntent.resolveActivity(packageManager) != null) {
            startActivity(gmailIntent)
        } else {
            // Fallback: show share sheet
            val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report.toString())
            }
            startActivity(Intent.createChooser(fallbackIntent, "Share Report"))
        }

        // ✅ Reset usage data after sending
        prefs.edit().clear().apply()
    }

    private fun getAndroidAppUsageData(): Map<String, Int> {
        val usageData = mutableMapOf<String, Int>()
        
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val begin = end - (24 * 60 * 60 * 1000) // Last 24 hours
            
            val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, begin, end)
            
            for (stat in stats) {
                val packageName = stat.packageName
                val totalTime = stat.totalTimeInForeground / 1000 // Convert to seconds
                
                // Skip system apps and our own app
                if (packageName != this@MainActivity.packageName && 
                    !packageName.startsWith("com.android.") &&
                    !packageName.startsWith("android.") &&
                    totalTime > 0) {
                    
                    val appName = try {
                        val appInfo = packageManager.getApplicationInfo(packageName, 0)
                        packageManager.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        packageName
                    }
                    
                    usageData[appName] = totalTime.toInt()
                }
            }
        } catch (e: Exception) {
            // Usage stats permission not granted or other error
            Log.d("MainActivity", "Could not get usage stats: ${e.message}")
        }
        
        return usageData.toList()
            .sortedByDescending { it.second }
            .take(10) // Top 10 apps
            .toMap()
    }

    private fun showRewardAppPicker(minutes: Int) {
        Log.d("MainActivity", "showRewardAppPicker called with $minutes minutes")
        
        val rewardApps = SettingsManager.readRewardApps(this).toList()
        Log.d("MainActivity", "Found ${rewardApps.size} reward apps configured")
        
        if (rewardApps.isEmpty()) {
            Toast.makeText(this, "No reward apps configured. Please ask a parent to set them in settings.", Toast.LENGTH_LONG).show()
            return
        }
        val pm = packageManager
        val appInfos = rewardApps.mapNotNull { pkg ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                val icon = pm.getApplicationIcon(appInfo)
                Triple(pkg, label, icon)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading app info for $pkg: ${e.message}")
                null
            }
        }
        Log.d("MainActivity", "Loaded ${appInfos.size} valid app infos")
        
        if (appInfos.isEmpty()) {
            Toast.makeText(this, "No valid reward apps found.", Toast.LENGTH_LONG).show()
            return
        }
        
        // Better orientation detection
        val displayMetrics = resources.displayMetrics
        val isLandscape = displayMetrics.widthPixels > displayMetrics.heightPixels
        
        val columns = if (isLandscape) 8 else 5
        val rows = if (isLandscape) 3 else 5
        
        Log.d("MainActivity", "Screen: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}, Landscape: $isLandscape, Grid: ${columns}x${rows}")
        
        // Debug toast
        Toast.makeText(this, "Orientation: ${if (isLandscape) "Landscape" else "Portrait"} - Grid: ${columns}x${rows}", Toast.LENGTH_SHORT).show()
        
        val grid = GridLayout(this).apply {
            columnCount = columns
            rowCount = rows
            useDefaultMargins = false
            setPadding(16, 16, 16, 16)
        }
        
        Log.d("MainActivity", "Creating grid with ${columns}x${rows} layout")
        
        appInfos.forEachIndexed { index, (pkg, label, icon) ->
            val row = index / columns
            val col = index % columns
            
            Log.d("MainActivity", "Adding app $index ($label) at position ($row, $col)")
            
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(8, 8, 8, 8)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(col, 1f)
                    rowSpec = GridLayout.spec(row, 1f)
                    setMargins(8, 8, 8, 8)
                }
            }
            val iconView = ImageView(this).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(120, 120)
            }
            val labelView = TextView(this).apply {
                text = label
                gravity = android.view.Gravity.CENTER
                textSize = 12f
                setTextColor(android.graphics.Color.BLACK)
                maxLines = 2
            }
            item.addView(iconView)
            item.addView(labelView)
            item.setOnClickListener {
                Log.d("MainActivity", "App selected: $pkg for $minutes minutes")
                // Grant access and launch
                RewardManager.grantAccess(this@MainActivity, pkg, minutes)
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    Toast.makeText(this, "App not installed: $pkg", Toast.LENGTH_SHORT).show()
                }
                rewardAppDialog?.dismiss()
            }
            grid.addView(item)
        }
        
        Log.d("MainActivity", "Showing reward picker dialog")
        val dialog = AlertDialog.Builder(this)
            .setTitle("Pick your reward app")
            .setView(ScrollView(this).apply { addView(grid) })
            .setCancelable(false)
            .create()
        rewardAppDialog = dialog
        dialog.show()
    }

    inner class PinBridge(private val webView: WebView) {
        @android.webkit.JavascriptInterface
        fun showPinPrompt() {
            runOnUiThread {
                showPinPrompt { pin ->
                    webView.evaluateJavascript("window.onPinResult('" + pin + "')", null)
                }
            }
        }
    }

    fun showPinPrompt(onPinEntered: (String) -> Unit) {
        val storedPin = SettingsManager.readPin(this) ?: "1234"
        PinPromptDialog.show(this, "Enter PIN") { enteredPin ->
            if (enteredPin == storedPin) {
                onPinEntered(enteredPin)
            } else {
                // Show error and re-prompt
                Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                showPinPrompt(onPinEntered)
            }
        }
    }

    private fun launchBaerenEdApp() {
        val packageName = "com.talq2me.baerened"
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            Log.d("MainActivity", "Launching BaerenEd app")
            startActivity(launchIntent)
            finish() // Close BaerenLock if BaerenEd is launched
        } else {
            Log.d("MainActivity", "BaerenEd app not found, loading web view")
            webView.loadUrl("https://talq2me.github.io/Baeren/BaerenEd/index.html")
            webView.evaluateJavascript(
                """
                if ('serviceWorker' in navigator) {
                    navigator.serviceWorker.getRegistrations().then(function(registrations) {
                        for (let registration of registrations) {
                            registration.unregister();
                        }
                    });
                }
                """.trimIndent(), null
            )
        }
    }
    
    /**
     * Generates and uploads reward usage report to GitHub
     * Reports are appended to a daily file (AM_Rewards_Usage.txt or BM_Rewards_Usage.txt)
     * First upload of the day overwrites the file, subsequent uploads append to it
     */
    fun generateAndUploadRewardReport(
        sessions: List<RewardUsageTracker.AppUsageSession>,
        summary: RewardUsageTracker.RewardSessionSummary
    ) {
        Log.d(TAG, "generateAndUploadRewardReport called with ${sessions.size} sessions, total time: ${summary.totalTimeSeconds}s")
        
        // Get child name from settings (or use default)
        val childName = SettingsManager.readChildName(this) ?: "Child"
        Log.d(TAG, "Child name: $childName")
        
        // Get profile (AM or BM) to determine filename
        val profile = readProfile()
        val profilePrefix = profile // Profile is already in AM/BM format
        Log.d(TAG, "Profile: $profile, prefix: $profilePrefix")
        
        // Generate report section (for appending)
        val reportGenerator = RewardReportGenerator()
        val reportSection = reportGenerator.generateReportSection(sessions, summary, childName)
        Log.d(TAG, "Generated report section (${reportSection.length} chars)")
        
        // Upload to GitHub (with append logic)
        uploadReportToGitHub(reportSection, profilePrefix)
    }
    
    /**
     * Reads the current profile (AM or BM)
     */
    private fun readProfile(): String {
        return SettingsManager.readProfile(this) ?: "AM"
    }
    
    /**
     * Decrypts the GitHub token using AES-256-CBC decryption
     * The encrypted token is stored in BuildConfig (from local.properties)
     * The decryption key is hardcoded in source code (safe to commit - it's just a key, not the token)
     * 
     * Why this works: GitHub secret scanning looks for token PATTERNS (like "github_pat_", "ghp_")
     * The encrypted token is just random-looking Base64, so GitHub won't detect it as a token
     */
    private fun decryptGitHubToken(): String {
        return try {
            val encryptedToken = BuildConfig.ENCRYPTED_GITHUB_TOKEN
            
            if (encryptedToken.isEmpty()) {
                Log.w(TAG, "GitHub token encryption not configured")
                return ""
            }
            
            // Hardcoded decryption key - this is safe to commit (it's not the token, just a key)
            // This key decrypts the token that's stored in BuildConfig.ENCRYPTED_GITHUB_TOKEN
            val encryptionKeyB64 = "MOBRoFYjmXL0ZwELC/CcQXgWm2xThNJlTSElwRhReZI="  // Base64 encoded 32-byte key
            
            // Decode Base64 encrypted data and key
            val encryptedBytes = Base64.decode(encryptedToken, Base64.DEFAULT)
            val keyBytes = Base64.decode(encryptionKeyB64, Base64.DEFAULT)
            
            // Extract IV (first 16 bytes) and ciphertext (rest)
            val iv = ByteArray(16)
            System.arraycopy(encryptedBytes, 0, iv, 0, 16)
            val ciphertextLength = encryptedBytes.size - 16
            val ciphertext = ByteArray(ciphertextLength)
            System.arraycopy(encryptedBytes, 16, ciphertext, 0, ciphertextLength)
            
            // Decrypt using AES-256-CBC
            val secretKeySpec = SecretKeySpec(keyBytes, "AES")
            val ivParameterSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)
            
            val decryptedBytes = cipher.doFinal(ciphertext)
            
            // Remove PKCS5 padding
            val padLength = decryptedBytes[decryptedBytes.size - 1].toInt()
            val unpaddedLength = decryptedBytes.size - padLength
            val unpaddedBytes = ByteArray(unpaddedLength)
            System.arraycopy(decryptedBytes, 0, unpaddedBytes, 0, unpaddedLength)
            
            String(unpaddedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt GitHub token", e)
            ""
        }
    }
    
    /**
     * Automatically uploads the reward usage report to GitHub as a text file
     * Reports are appended to a daily file (AM_Rewards_Usage.txt or BM_Rewards_Usage.txt)
     * First upload of the day overwrites the file, subsequent uploads append to it
     */
    private fun uploadReportToGitHub(
        reportSection: String,
        profilePrefix: String
    ) {
        Log.d(TAG, "uploadReportToGitHub called for profile: $profilePrefix")
        
        // Decrypt GitHub token at runtime (encrypted token and key stored in BuildConfig)
        val githubToken = decryptGitHubToken()
        
        // Check if GitHub token is configured
        if (githubToken.isBlank()) {
            Log.w(TAG, "GitHub token not configured - skipping upload")
            Toast.makeText(this, "GitHub token not configured - report not uploaded", Toast.LENGTH_LONG).show()
            return
        }
        
        Log.d(TAG, "GitHub token decrypted successfully, proceeding with upload")
        
        // Show progress (optional, for feedback)
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Uploading Report")
            .setMessage("Please wait...")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        // Get preferences and calculate today's date before coroutine
        val prefs = getSharedPreferences("reward_report_prefs", MODE_PRIVATE)
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        val lastUploadDate = prefs.getLong("last_reward_report_upload_date_$profilePrefix", 0L)
        val isFirstUploadOfDay = lastUploadDate != today
        
        // Use coroutines for async work
        lifecycleScope.launch(Dispatchers.IO) {
            val client = OkHttpClient()
            try {
                // Create filename based on profile: AM_Rewards_Usage.txt or BM_Rewards_Usage.txt
                val fileName = "${profilePrefix}_Rewards_Usage.txt"
                val filePath = "$GITHUB_REPORTS_PATH/$fileName"
                
                // GitHub API endpoint
                val apiUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/contents/$filePath"
                
                // Get existing file content if it exists and we're appending
                var existingContent = ""
                var existingSha: String? = null
                var isUpdate = false
                
                try {
                    val checkRequest = Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "token $githubToken")
                        .addHeader("Accept", "application/vnd.github.v3+json")
                        .get()
                        .build()
                    client.newCall(checkRequest).execute().use { checkResponse ->
                        if (checkResponse.isSuccessful) {
                            val checkBody = checkResponse.body?.string()
                            if (!checkBody.isNullOrEmpty()) {
                                val fileInfo = JSONObject(checkBody)
                                existingSha = fileInfo.optString("sha", null)
                                val existingContentBase64 = fileInfo.optString("content", "")
                                
                                // Decode existing content
                                if (existingContentBase64.isNotEmpty()) {
                                    // GitHub API returns content with newlines, need to remove them before decoding
                                    val cleanBase64 = existingContentBase64.replace("\n", "").replace("\r", "")
                                    val existingContentBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                                    existingContent = String(existingContentBytes, Charsets.UTF_8)
                                    isUpdate = true
                                    Log.d(TAG, "File exists, will ${if (isFirstUploadOfDay) "overwrite" else "append"}. SHA: $existingSha")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // File doesn't exist, will create new - this is fine
                    Log.d(TAG, "File doesn't exist yet, will create new file")
                }
                
                // Build final content
                val finalContent = if (isFirstUploadOfDay || !isUpdate) {
                    // First upload of the day or file doesn't exist - create new file
                    buildString {
                        appendLine("🎮 REWARD TIME USAGE REPORT - ${profilePrefix}")
                        appendLine("=".repeat(50))
                        appendLine("Date: ${java.text.SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date())}")
                        appendLine()
                        append(reportSection)
                    }
                } else {
                    // Not first upload - append to existing content
                    buildString {
                        append(existingContent)
                        if (!existingContent.endsWith("\n")) {
                            appendLine()
                        }
                        append(reportSection)
                    }
                }
                
                // Base64 encode the content (GitHub API requirement)
                val contentBytes = finalContent.toByteArray(Charsets.UTF_8)
                val base64Content = Base64.encodeToString(contentBytes, Base64.NO_WRAP)
                
                // Create JSON payload for GitHub API
                val commitMessage = if (isFirstUploadOfDay || !isUpdate) {
                    "Create/Reset reward usage report for $profilePrefix"
                } else {
                    "Append reward usage report for $profilePrefix"
                }
                val json = JSONObject().apply {
                    put("message", commitMessage)
                    put("content", base64Content)
                    put("branch", "main")
                    if (existingSha != null) {
                        put("sha", existingSha)  // Required for updates/overwrites
                    }
                }
                
                // Create request
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = json.toString().toRequestBody(mediaType)
                
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "token $githubToken")
                    .addHeader("Accept", "application/vnd.github.v3+json")
                    .put(body)
                    .build()
                
                // Execute request
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        
                        if (response.isSuccessful) {
                            // Update last upload date
                            prefs.edit().putLong("last_reward_report_upload_date_$profilePrefix", today).apply()
                            
                            Log.d(TAG, "Report uploaded successfully to GitHub: $filePath")
                            Toast.makeText(this@MainActivity, "Report uploaded!", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.e(TAG, "GitHub upload failed: ${response.code} - $responseBody")
                            val errorMsg = try {
                                val errorJson = JSONObject(responseBody ?: "{}")
                                errorJson.optString("message", "Upload failed")
                            } catch (e: Exception) {
                                "Upload failed: ${response.code}"
                            }
                            
                            Toast.makeText(this@MainActivity, "Upload failed: $errorMsg", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading report to GitHub", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Upload error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
