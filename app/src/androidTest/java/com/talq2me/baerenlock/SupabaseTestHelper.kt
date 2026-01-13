package com.talq2me.baerenlock

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.runBlocking
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * Helper class for querying Supabase database in tests
 * Provides methods to verify database state after UI operations
 */
object SupabaseTestHelper {
    private const val TAG = "SupabaseTestHelper"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Gets Supabase URL from BuildConfig
     */
    private fun getSupabaseUrl(context: Context): String {
        return try {
            BuildConfig.SUPABASE_URL.ifBlank { "" }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Supabase URL from BuildConfig", e)
            ""
        }
    }

    /**
     * Gets Supabase API key from BuildConfig
     */
    private fun getSupabaseKey(context: Context): String {
        return try {
            BuildConfig.SUPABASE_KEY.ifBlank { "" }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Supabase key from BuildConfig", e)
            ""
        }
    }

    /**
     * Checks if Supabase is configured
     */
    fun isConfigured(context: Context): Boolean {
        val url = getSupabaseUrl(context)
        val key = getSupabaseKey(context)
        return url.isNotBlank() && key.isNotBlank()
    }

    /**
     * Queries settings table and returns the settings data
     * Returns null if not configured or query fails
     */
    fun querySettings(context: Context): Map<String, Any>? {
        if (!isConfigured(context)) {
            Log.w(TAG, "Supabase not configured, skipping query")
            return null
        }

        return runBlocking {
            try {
                val url = "${getSupabaseUrl(context)}/rest/v1/settings?id=eq.1&select=*"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("apikey", getSupabaseKey(context))
                    .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "[]"
                    response.close()

                    if (responseBody != "[]" && responseBody != "{}") {
                        val settingsArray = gson.fromJson(
                            responseBody,
                            object : TypeToken<Array<Map<String, Any>>>() {}.type
                        ) as? Array<Map<String, Any>>
                        return@runBlocking settingsArray?.firstOrNull()
                    }
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Log.e(TAG, "Failed to query settings: ${response.code} - $errorBody")
                    response.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying settings from Supabase", e)
            }
            null
        }
    }

    /**
     * Queries user_data table for a specific profile
     * Returns null if not configured or query fails
     */
    fun queryUserData(context: Context, profile: String): Map<String, Any>? {
        if (!isConfigured(context)) {
            Log.w(TAG, "Supabase not configured, skipping query")
            return null
        }

        return runBlocking {
            try {
                val url = "${getSupabaseUrl(context)}/rest/v1/user_data?profile=eq.$profile&select=*"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("apikey", getSupabaseKey(context))
                    .addHeader("Authorization", "Bearer ${getSupabaseKey(context)}")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "[]"
                    response.close()

                    if (responseBody != "[]" && responseBody != "{}") {
                        val dataList = gson.fromJson(
                            responseBody,
                            object : TypeToken<List<Map<String, Any>>>() {}.type
                        ) as? List<Map<String, Any>>
                        return@runBlocking dataList?.firstOrNull()
                    }
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Log.e(TAG, "Failed to query user_data: ${response.code} - $errorBody")
                    response.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying user_data from Supabase", e)
            }
            null
        }
    }

    /**
     * Gets parent_email from settings table
     */
    fun getParentEmail(context: Context): String? {
        val settings = querySettings(context)
        return settings?.get("parent_email") as? String
    }

    /**
     * Gets whitelisted apps from user_data table for a profile
     * Returns empty list if not found or not configured
     */
    fun getWhitelistedApps(context: Context, profile: String): List<String> {
        val userData = queryUserData(context, profile) ?: return emptyList()
        val whiteListedAppsJson = userData["white_listed_apps"] as? String ?: return emptyList()
        
        return try {
            val list = gson.fromJson(whiteListedAppsJson, object : TypeToken<List<String>>() {}.type) as? List<String>
            list ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing white_listed_apps JSON", e)
            emptyList()
        }
    }

    /**
     * Gets blacklisted apps from user_data table for a profile
     * Returns empty list if not found or not configured
     */
    fun getBlacklistedApps(context: Context, profile: String): List<String> {
        val userData = queryUserData(context, profile) ?: return emptyList()
        val blacklistedAppsJson = userData["blacklisted_apps"] as? String ?: return emptyList()
        
        return try {
            val list = gson.fromJson(blacklistedAppsJson, object : TypeToken<List<String>>() {}.type) as? List<String>
            list ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing blacklisted_apps JSON", e)
            emptyList()
        }
    }

    /**
     * Gets reward apps from user_data table for a profile
     * Returns empty list if not found or not configured
     */
    fun getRewardApps(context: Context, profile: String): List<String> {
        val userData = queryUserData(context, profile) ?: return emptyList()
        val rewardAppsJson = userData["reward_apps"] as? String ?: return emptyList()
        
        return try {
            val list = gson.fromJson(rewardAppsJson, object : TypeToken<List<String>>() {}.type) as? List<String>
            list ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing reward_apps JSON", e)
            emptyList()
        }
    }

    /**
     * Waits for database sync with retries
     * Useful when UI operations trigger async database updates
     */
    fun waitForDatabaseSync(
        context: Context,
        maxWaitSeconds: Int = 10,
        checkIntervalMs: Long = 500,
        condition: () -> Boolean
    ): Boolean {
        val startTime = System.currentTimeMillis()
        val maxWaitMs = maxWaitSeconds * 1000L

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            if (condition()) {
                return true
            }
            Thread.sleep(checkIntervalMs)
        }
        return false
    }
}
