package com.talq2me.baerenlock

import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Monitors the health status of critical services and permissions required for BaerenLock.
 * Detects when accessibility service or permissions are not working properly.
 */
object ServiceHealthMonitor {
    private const val TAG = "ServiceHealthMonitor"
    
    /**
     * Status of a service or permission
     */
    enum class HealthStatus {
        HEALTHY,      // Service/permission is enabled and working
        DISABLED,     // Service/permission is disabled
        ERROR         // Error checking status
    }
    
    /**
     * Health check results
     */
    data class HealthCheckResult(
        val accessibilityStatus: HealthStatus,
        val usageStatsStatus: HealthStatus,
        val defaultLauncherStatus: HealthStatus,
        val overlayPermissionStatus: HealthStatus,
        val batteryOptimizationStatus: HealthStatus,
        val accessibilityServiceName: String? = null,
        val lastCheckTime: Long = System.currentTimeMillis()
    ) {
        fun isHealthy(): Boolean {
            return accessibilityStatus == HealthStatus.HEALTHY 
                && usageStatsStatus != HealthStatus.ERROR
                && defaultLauncherStatus == HealthStatus.HEALTHY
                && overlayPermissionStatus == HealthStatus.HEALTHY
                && batteryOptimizationStatus == HealthStatus.HEALTHY
        }
        
        fun hasIssues(): Boolean {
            return accessibilityStatus != HealthStatus.HEALTHY 
                || usageStatsStatus == HealthStatus.DISABLED
                || defaultLauncherStatus != HealthStatus.HEALTHY
                || overlayPermissionStatus != HealthStatus.HEALTHY
                || batteryOptimizationStatus != HealthStatus.HEALTHY
        }
        
        fun getIssueDescription(): String {
            val issues = mutableListOf<String>()
            if (accessibilityStatus != HealthStatus.HEALTHY) {
                issues.add("Accessibility service is ${accessibilityStatus.name.lowercase()}")
            }
            if (usageStatsStatus == HealthStatus.DISABLED) {
                issues.add("Usage stats permission is disabled")
            }
            if (defaultLauncherStatus != HealthStatus.HEALTHY) {
                issues.add("Not set as default launcher")
            }
            if (overlayPermissionStatus != HealthStatus.HEALTHY) {
                issues.add("Overlay permission (display over other apps) is ${overlayPermissionStatus.name.lowercase()}")
            }
            if (batteryOptimizationStatus != HealthStatus.HEALTHY) {
                issues.add("Battery optimization is not disabled")
            }
            return issues.joinToString(", ")
        }
    }
    
    /**
     * Checks if the accessibility service is enabled and properly configured
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val expectedService = "${context.packageName}/${AppBlockerService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            val isEnabled = enabledServices?.split(':')?.any { it.equals(expectedService, ignoreCase = true) } ?: false
            Log.d(TAG, "Accessibility service check: expected=$expectedService, enabled=$isEnabled, enabledServices=$enabledServices")
            isEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility service status", e)
            false
        }
    }
    
    /**
     * Checks if usage stats permission is granted
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.e(TAG, "Error checking usage stats permission", e)
            false
        }
    }
    
    /**
     * Checks if BaerenLock is set as the default launcher
     */
    fun isDefaultLauncher(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            
            // Resolve the HOME intent to see which launcher is currently default
            val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            
            if (resolveInfo != null) {
                val defaultPackage = resolveInfo.activityInfo.packageName
                val isDefault = defaultPackage == context.packageName
                Log.d(TAG, "Default launcher check: current=$defaultPackage, ourPackage=${context.packageName}, isDefault=$isDefault")
                return isDefault
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking default launcher: ${e.message}", e)
            false
        }
    }
    
    /**
     * Checks if overlay permission (display over other apps) is granted
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true // Permission not required on older Android versions
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking overlay permission", e)
            false
        }
    }
    
    /**
     * Checks if battery optimization is disabled (app is ignoring battery optimizations)
     * Also checks if the app is allowed to run in the background (some devices use this instead)
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val ignoringOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName)
                
                Log.d(TAG, "Battery optimization check: isIgnoringBatteryOptimizations=$ignoringOptimizations for ${context.packageName}")
                
                // Also check if app is allowed to run in background (some devices use this setting)
                // This is a fallback for devices where "run in background" is the equivalent setting
                // OPSTR_RUN_IN_BACKGROUND is available from API 26 (Android 8.0)
                if (!ignoringOptimizations && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                        // Use string constant for compatibility across API levels
                        val runInBackgroundOp = "android:run_in_background"
                        
                        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            appOps.unsafeCheckOpNoThrow(
                                runInBackgroundOp,
                                android.os.Process.myUid(),
                                context.packageName
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            appOps.checkOpNoThrow(
                                runInBackgroundOp,
                                android.os.Process.myUid(),
                                context.packageName
                            )
                        }
                        
                        Log.d(TAG, "Run in background check: mode=$mode (MODE_ALLOWED=${AppOpsManager.MODE_ALLOWED})")
                        
                        // If run in background is allowed, consider it equivalent to ignoring battery optimizations
                        if (mode == AppOpsManager.MODE_ALLOWED) {
                            Log.d(TAG, "Battery optimization check: ignoringOptimizations=false, but run_in_background=ALLOWED, treating as healthy")
                            return true
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Could not check run_in_background permission: ${e.message}")
                    }
                }
                
                ignoringOptimizations
            } else {
                true // Not applicable on older Android versions
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking battery optimization status", e)
            false
        }
    }
    
    /**
     * Performs a comprehensive health check of all required services and permissions
     */
    fun performHealthCheck(context: Context): HealthCheckResult {
        val accessibilityEnabled = isAccessibilityServiceEnabled(context)
        val usageStatsGranted = hasUsageStatsPermission(context)
        val isDefaultLauncher = isDefaultLauncher(context)
        val hasOverlay = hasOverlayPermission(context)
        val ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
        
        return HealthCheckResult(
            accessibilityStatus = if (accessibilityEnabled) {
                HealthStatus.HEALTHY
            } else {
                HealthStatus.DISABLED
            },
            usageStatsStatus = when {
                usageStatsGranted -> HealthStatus.HEALTHY
                else -> HealthStatus.DISABLED
            },
            defaultLauncherStatus = if (isDefaultLauncher) {
                HealthStatus.HEALTHY
            } else {
                HealthStatus.DISABLED
            },
            overlayPermissionStatus = if (hasOverlay) {
                HealthStatus.HEALTHY
            } else {
                HealthStatus.DISABLED
            },
            batteryOptimizationStatus = if (ignoringBatteryOptimizations) {
                HealthStatus.HEALTHY
            } else {
                HealthStatus.DISABLED
            },
            accessibilityServiceName = AppBlockerService::class.java.name,
            lastCheckTime = System.currentTimeMillis()
        )
    }
    
    /**
     * Checks if accessibility service is actually running (more reliable check)
     * This can be called from within the accessibility service itself
     */
    fun isServiceCurrentlyRunning(service: AccessibilityService?): Boolean {
        return try {
            service != null && service.serviceInfo != null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if service is running", e)
            false
        }
    }
}
