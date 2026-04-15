package com.talq2me.baerenlock

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Before UI that shows DB-backed values: **(1)** `af_daily_reset(profile)`, **(2)** fetch user row +
 * settings via existing Supabase/RPC helpers and apply locally. No client timestamp tricks or
 * duplicate reset inside the download path.
 */
object DbUserDataRefresh {

    private const val TAG = "BaerenLockDbRefresh"

    suspend fun runDailyResetThenFetchUserData(context: Context, profile: String) = withContext(Dispatchers.IO) {
        if (!SupabaseInterface.isConfigured(context)) {
            Log.d(TAG, "Supabase not configured, skip refresh")
            return@withContext
        }
        val resetOk = SupabaseInterface.runAfDailyResetRpc(context, profile)
        if (!resetOk) {
            Log.w(TAG, "af_daily_reset failed for $profile; still attempting reads")
        }
        SupabaseInterface.downloadUserDataFromCloud(
            context,
            profile,
            invokeDailyResetRpc = false
        )
        SupabaseInterface.loadSettingsFromCloud(context)
        SupabaseInterface.getActiveProfileFromCloud(context)?.let { profileData: SupabaseInterface.ProfileWithTimestamp ->
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit()
                .putString("profile", profileData.profile)
                .apply()
        }
        Log.d(TAG, "runDailyResetThenFetchUserData completed for $profile")
    }
}
