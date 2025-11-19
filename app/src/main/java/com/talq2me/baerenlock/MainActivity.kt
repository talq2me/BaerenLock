package com.talq2me.baerenlock

import android.content.pm.PackageInstaller
import android.app.PendingIntent
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
// download updates 
import android.app.DownloadManager
import android.os.Environment
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.talq2me.baerenlock.DevicePolicyManager as CustomDevicePolicyManager
import java.io.File
import org.json.JSONObject
import java.net.URL

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        fun checkForUpdate(context: Context) {
            checkForUpdate(context, false)
        }
        
        fun checkForUpdate(context: Context, force: Boolean = false) {
            // Throttle update checks to avoid excessive network requests
            // Only check once per hour unless forced
            val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
            val lastCheckTime = prefs.getLong("last_update_check", 0L)
            val now = System.currentTimeMillis()
            val oneHourInMs = 60 * 60 * 1000L
            
            if (!force && (now - lastCheckTime) < oneHourInMs) {
                Log.d("MainActivity", "Skipping update check - last checked ${(now - lastCheckTime) / 1000 / 60} minutes ago")
                return
            }
            
            // Save the check time
            prefs.edit().putLong("last_update_check", now).apply()
            
            Thread {
                try {
                    // Check for internet first
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val network = cm.activeNetwork
                    if (network == null) {
                        Log.d("MainActivity", "No internet — skipping update check.")
                        return@Thread
                    }

                    Log.d("MainActivity", "Checking for updates...")
                    // Download JSON from GitHub Pages
                    val jsonText = URL("https://raw.githubusercontent.com/talq2me/BaerenLock/refs/heads/main/release-config/version.json")
                        .readText()

                    val json = JSONObject(jsonText)
                    val latestVersion = json.getInt("latestVersionCode")
                    val apkUrl = json.getString("apkUrl")

                    val currentVersion = context.packageManager
                        .getPackageInfo(context.packageName, 0).longVersionCode

                    Log.d("MainActivity", "Update check: current=$currentVersion, latest=$latestVersion")

                    if (latestVersion > currentVersion) {
                        // Force the update
                        if (context is MainActivity) {
                            context.runOnUiThread {
                                AlertDialog.Builder(context)
                                    .setTitle("Update Required")
                                    .setMessage("A new version is available and must be installed to continue.")
                                    .setCancelable(false)
                                    .setPositiveButton("Update") { _, _ ->
                                        downloadAndInstall(context, apkUrl)
                                    }
                                    .show()
                            }
                        } else if (context is LauncherActivity) {
                            context.runOnUiThread {
                                AlertDialog.Builder(context)
                                    .setTitle("Update Required")
                                    .setMessage("A new version is available and must be installed to continue.")
                                    .setCancelable(false)
                                    .setPositiveButton("Update") { _, _ ->
                                        // Download and install the update
                                        downloadAndInstall(context, apkUrl)
                                    }
                                    .show()
                            }
                        } else {
                            // Generic context - try to show dialog if it's an Activity
                            if (context is android.app.Activity) {
                                context.runOnUiThread {
                                    AlertDialog.Builder(context)
                                        .setTitle("Update Required")
                                        .setMessage("A new version is available and must be installed to continue.")
                                        .setCancelable(false)
                                        .setPositiveButton("Update") { _, _ ->
                                            downloadAndInstall(context, apkUrl)
                                        }
                                        .show()
                                }
                            }
                        }
                    } else {
                        Log.d("MainActivity", "App is up to date")
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Update check error: ${e.message}", e)
                }
            }.start()
        }

        fun downloadAndInstall(context: Context, apkUrl: String) {
            val request = DownloadManager.Request(Uri.parse(apkUrl))
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "update.apk")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setMimeType("application/vnd.android.package-archive")

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            
            // If called from MainActivity, store the ID for the receiver
            if (context is MainActivity) {
                (context as MainActivity).updateDownloadId = downloadId
            } else {
                // Store in SharedPreferences so MainActivity can pick it up if it's running
                val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                prefs.edit().putLong("update_download_id", downloadId).apply()
            }

            // System installer will take over automatically when user taps the notification
            Log.d("MainActivity", "Update download started: ID=$downloadId")
        }
    }

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private var rewardAppDialog: AlertDialog? = null
    private var updateDownloadId: Long = -1L
    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
            
            // Check if this matches our tracked download ID or one from SharedPreferences
            val prefs = getSharedPreferences("update_prefs", MODE_PRIVATE)
            val savedDownloadId = prefs.getLong("update_download_id", -1L)
            
            if ((id == updateDownloadId && updateDownloadId != -1L) || 
                (id == savedDownloadId && savedDownloadId != -1L)) {
                // Clear the saved ID
                prefs.edit().remove("update_download_id").apply()
                updateDownloadId = id
                handleDownloadedUpdate()
            }
        }
    }

    private lateinit var requestOverlayPermissionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkForUpdate(this)

        registerUpdateReceiver()

        requestOverlayPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
            }
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

    }

    private fun registerUpdateReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateDownloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(updateDownloadReceiver, filter)
        }
    }

    private fun handleDownloadedUpdate() {
        updateDownloadId = -1L
        val apkFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        if (!apkFile.exists()) {
            Toast.makeText(this, "Update download missing.", Toast.LENGTH_LONG).show()
            return
        }

        val devicePolicyManager = CustomDevicePolicyManager.getInstance(this)
        if (devicePolicyManager.isDeviceOwnerActive()) {
            installSilently(apkFile)
        } else {
            promptManualInstall(apkFile)
        }
    }

    private fun installSilently(apkFile: File) {
        try {
            val packageInstaller = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val statusIntent = Intent(this, InstallResultReceiver::class.java)
                val pendingIntent = PendingIntent.getBroadcast(
                    this,
                    sessionId,
                    statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Silent install failed", e)
            promptManualInstall(apkFile)
        }
    }

    private fun promptManualInstall(apkFile: File) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Install Update")
                .setMessage("A new version is ready to install.")
                .setCancelable(false)
                .setPositiveButton("Install") { _, _ ->
                    launchManualInstallIntent(apkFile)
                }
                .show()
        }
    }

    private fun launchManualInstallIntent(apkFile: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, "Allow installs then tap Install again.", Toast.LENGTH_LONG).show()
            return
        }

        val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = apkUri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        startActivity(installIntent)
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
        // Check for updates when coming to foreground (throttled to once per hour)
        checkForUpdate(this)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(updateDownloadReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("MainActivity", "Receiver already unregistered")
        }
        tts.stop()
        tts.shutdown()
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
        val settingsPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        val parentEmail = settingsPrefs.getString("parent_email", null)
        
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
        
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val rewardApps = prefs.getStringSet("reward_apps", emptySet())?.toList() ?: emptyList()
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
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val storedPin = prefs.getString("parent_pin", "1234") ?: "1234"
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
}
