package com.shreddro.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.shreddro.app.BuildConfig

/**
 * User-supplied runtime configuration, stored in Keystore-encrypted prefs.
 *
 * These values deliberately do NOT ship inside the APK: this is a public
 * open-source app, and anything compiled into a released binary is extractable
 * by anyone. Each user brings their own Gemini API key and their own Apps
 * Script deployment ("your cloud, your keys" — no Shreddro servers).
 *
 * The BuildConfig fields remain as a developer convenience: a value in
 * local.properties seeds local debug builds, but the in-app setting always
 * wins once set. CI never needs any of these.
 */
class AppSettings(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "shreddro_config",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.GEMINI_API_KEY
        set(value) = prefs.edit().putString(KEY_GEMINI, value.trim()).apply()

    var appsScriptUrl: String
        get() = prefs.getString(KEY_SCRIPT_URL, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.APPS_SCRIPT_URL
        set(value) = prefs.edit().putString(KEY_SCRIPT_URL, value.trim()).apply()

    var appsScriptSecret: String
        get() = prefs.getString(KEY_SCRIPT_SECRET, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.APPS_SCRIPT_SECRET
        set(value) = prefs.edit().putString(KEY_SCRIPT_SECRET, value.trim()).apply()

    var msWorkbookItemId: String
        get() = prefs.getString(KEY_MS_WORKBOOK, null) ?: ""
        set(value) = prefs.edit().putString(KEY_MS_WORKBOOK, value.trim()).apply()

    private companion object {
        const val KEY_GEMINI = "gemini_api_key"
        const val KEY_SCRIPT_URL = "apps_script_url"
        const val KEY_SCRIPT_SECRET = "apps_script_secret"
        const val KEY_MS_WORKBOOK = "ms_workbook_item_id"
    }
}
