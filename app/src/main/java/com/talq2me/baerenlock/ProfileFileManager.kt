package com.talq2me.baerenlock

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.IOException

object ProfileFileManager {
    private const val PROFILE_FILE_NAME = "baeren_profile.txt"
    private const val TAG = "ProfileFileManager"

    private fun getProfileFile(context: Context): File {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val baerenDir = File(documentsDir, "Baeren")
        if (!baerenDir.exists()) {
            baerenDir.mkdirs()
        }
        return File(baerenDir, PROFILE_FILE_NAME)
    }

    fun readProfile(context: Context): String? {
        val profileFile = getProfileFile(context)
        return try {
            if (profileFile.exists()) {
                val profile = profileFile.readText().trim()
                Log.d(TAG, "Read profile: $profile")
                profile
            } else {
                Log.d(TAG, "Profile file does not exist")
                null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading profile file", e)
            null
        }
    }

    fun writeProfile(context: Context, profile: String) {
        val profileFile = getProfileFile(context)
        try {
            profileFile.writeText(profile)
            Log.d(TAG, "Wrote profile: $profile")
        } catch (e: IOException) {
            Log.e(TAG, "Error writing profile file", e)
        }
    }

    fun hasProfile(context: Context): Boolean {
        return readProfile(context) != null
    }
}
