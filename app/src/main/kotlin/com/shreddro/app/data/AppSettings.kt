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
 * by anyone. Each user brings their own Apps Script deployment
 * ("your cloud, your keys" — no Shreddro servers).
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

    // Deep-link targets learned from successful syncs; blank until first sync.
    var googleSheetUrl: String
        get() = prefs.getString(KEY_SHEET_URL, null) ?: ""
        set(value) = prefs.edit().putString(KEY_SHEET_URL, value.trim()).apply()

    var googleDriveFolderUrl: String
        get() = prefs.getString(KEY_DRIVE_URL, null) ?: ""
        set(value) = prefs.edit().putString(KEY_DRIVE_URL, value.trim()).apply()

    var excelWorkbookUrl: String
        get() = prefs.getString(KEY_EXCEL_URL, null) ?: ""
        set(value) = prefs.edit().putString(KEY_EXCEL_URL, value.trim()).apply()

    var oneDriveFolderUrl: String
        get() = prefs.getString(KEY_ONEDRIVE_URL, null) ?: ""
        set(value) = prefs.edit().putString(KEY_ONEDRIVE_URL, value.trim()).apply()

    /**
     * Upload downsized JPEG copies to Drive/OneDrive (default on). The local
     * archive is always the byte-exact original regardless of this flag.
     */
    var compressUploads: Boolean
        get() = prefs.getBoolean(KEY_COMPRESS_UPLOADS, true)
        set(value) = prefs.edit().putBoolean(KEY_COMPRESS_UPLOADS, value).apply()

    private companion object {
        const val KEY_COMPRESS_UPLOADS = "compress_uploads"
        const val KEY_SHEET_URL = "google_sheet_url"
        const val KEY_DRIVE_URL = "google_drive_folder_url"
        const val KEY_EXCEL_URL = "excel_workbook_url"
        const val KEY_ONEDRIVE_URL = "onedrive_folder_url"
        const val KEY_SCRIPT_URL = "apps_script_url"
        const val KEY_SCRIPT_SECRET = "apps_script_secret"
        const val KEY_MS_WORKBOOK = "ms_workbook_item_id"
    }
}
