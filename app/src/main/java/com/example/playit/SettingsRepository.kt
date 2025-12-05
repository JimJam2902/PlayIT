@file:Suppress("DEPRECATION")

package com.example.playit

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit

/**
 * Simple settings repository using EncryptedSharedPreferences for storing OpenSubtitles credentials.
 */
class SettingsRepository(context: Context) {
    private val prefsName = "secure_settings"
    // NOTE: In this workspace the security-crypto APIs are marked deprecated by the IDE
    // (this is a warning only). The APIs still work; migrate when an official replacement is
    // available from AndroidX. Suppress the deprecation warnings locally to keep the code clean.
    @Suppress("DEPRECATION")
    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    @Suppress("DEPRECATION")
    private val prefs = EncryptedSharedPreferences.create(
        context,
        prefsName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_OS_USERNAME = "opensubtitles_username"
        private const val KEY_OS_PASSWORD = "opensubtitles_password"
    }

    fun saveOpenSubtitlesCredentials(username: String, password: String) {
        // Use KTX edit extension for clearer code and to avoid the non-KTX warning.
        prefs.edit(commit = true) {
            putString(KEY_OS_USERNAME, username)
            putString(KEY_OS_PASSWORD, password)
        }
    }

    fun getOpenSubtitlesUsername(): String? = prefs.getString(KEY_OS_USERNAME, null)
    fun getOpenSubtitlesPassword(): String? = prefs.getString(KEY_OS_PASSWORD, null)

    fun clearOpenSubtitlesCredentials() {
        prefs.edit(commit = true) {
            remove(KEY_OS_USERNAME)
            remove(KEY_OS_PASSWORD)
        }
    }
}
