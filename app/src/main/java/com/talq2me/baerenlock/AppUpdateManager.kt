package com.talq2me.baerenlock

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Device-owner silent updates for BaerenEd and BaerenLock.
 * Polls version manifests and installs newer APKs via PackageInstaller.
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    private const val BAEREN_ED_PACKAGE = "com.talq2me.baerened"
    private const val BAEREN_LOCK_PACKAGE = "com.talq2me.baerenlock"

    private const val BAEREN_ED_MANIFEST_URL =
        "https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/config/version.json"
    private const val BAEREN_LOCK_MANIFEST_URL =
        "https://raw.githubusercontent.com/talq2me/BaerenLock/main/release-config/version.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val updateInFlight = AtomicBoolean(false)

    data class UpdateManifest(
        val packageName: String,
        val latestVersionCode: Int,
        val apkUrl: String,
    )

    suspend fun checkAndApplyUpdates(context: Context) = withContext(Dispatchers.IO) {
        if (!updateInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "Update check already in progress")
            return@withContext
        }
        try {
            if (!isOnline(context)) {
                Log.d(TAG, "Offline — skipping update check")
                return@withContext
            }
            if (!isDeviceOwner(context)) {
                Log.w(TAG, "Not device owner — silent updates disabled")
                return@withContext
            }

            val edManifest = fetchManifest(BAEREN_ED_MANIFEST_URL, BAEREN_ED_PACKAGE)
            if (edManifest != null) {
                maybeUpdate(context, edManifest)
            }

            val lockManifest = fetchManifest(BAEREN_LOCK_MANIFEST_URL, BAEREN_LOCK_PACKAGE)
            if (lockManifest != null) {
                maybeUpdate(context, lockManifest)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
        } finally {
            updateInFlight.set(false)
        }
    }

    private suspend fun maybeUpdate(context: Context, manifest: UpdateManifest) {
        val installed = getInstalledVersionCode(context, manifest.packageName)
        if (installed == null) {
            Log.w(TAG, "Package ${manifest.packageName} not installed — skipping")
            return
        }
        if (manifest.latestVersionCode <= installed) {
            Log.d(TAG, "${manifest.packageName} up to date ($installed)")
            return
        }

        Log.i(TAG, "Updating ${manifest.packageName}: $installed -> ${manifest.latestVersionCode}")
        val apkFile = downloadApk(context, manifest)
        if (apkFile == null) {
            Log.e(TAG, "Download failed for ${manifest.packageName}")
            return
        }
        val installedOk = installSilently(context, apkFile, manifest.packageName)
        if (installedOk) {
            Log.i(TAG, "Silent install committed for ${manifest.packageName}")
        } else {
            Log.e(TAG, "Silent install failed for ${manifest.packageName}")
            apkFile.delete()
        }
    }

    private fun fetchManifest(url: String, defaultPackage: String): UpdateManifest? {
        return try {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Manifest HTTP ${response.code} for $url")
                return null
            }
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val packageName = json.optString("package", defaultPackage)
            val latestVersionCode = json.getInt("latestVersionCode")
            val apkUrl = json.getString("apkUrl")
            UpdateManifest(packageName, latestVersionCode, apkUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch manifest $url", e)
            null
        }
    }

    private fun getInstalledVersionCode(context: Context, packageName: String): Int? {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun downloadApk(context: Context, manifest: UpdateManifest): File? {
        val updatesDir = File(context.noBackupFilesDir, "app_updates").apply { mkdirs() }
        val target = File(updatesDir, "${manifest.packageName}-v${manifest.latestVersionCode}.apk")
        if (target.exists()) {
            target.delete()
        }
        return try {
            val response = client.newCall(Request.Builder().url(manifest.apkUrl).build()).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "APK HTTP ${response.code} for ${manifest.apkUrl}")
                return null
            }
            val body = response.body ?: return null
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            target
        } catch (e: IOException) {
            Log.e(TAG, "APK download error for ${manifest.packageName}", e)
            if (target.exists()) {
                target.delete()
            }
            null
        }
    }

    private fun installSilently(context: Context, apkFile: File, targetPackage: String): Boolean {
        if (!apkFile.exists()) {
            return false
        }
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val sessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sessionParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = packageInstaller.createSession(sessionParams)
            val session = packageInstaller.openSession(sessionId)
            FileInputStream(apkFile).use { input ->
                session.openWrite("package", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callbackIntent = Intent(context, InstallResultReceiver::class.java).apply {
                putExtra(InstallResultReceiver.EXTRA_TARGET_PACKAGE, targetPackage)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, callbackIntent, flags)
            session.commit(pendingIntent.intentSender)
            session.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller session failed for $targetPackage", e)
            false
        }
    }

    private fun isDeviceOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
