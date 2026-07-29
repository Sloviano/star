package com.starlink.scanner.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.starlink.scanner.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** App settings backed by DataStore (Preferences): Apps Script URL and upload diagnostics. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val SHEETS_URL = stringPreferencesKey("sheets_url")
        val LAST_UPLOAD = longPreferencesKey("last_upload")
        val LAST_UPLOAD_ERROR = stringPreferencesKey("last_upload_error")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val SKIPPED_VERSION_CODE = longPreferencesKey("skipped_version_code")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
    }

    val sheetsUrl: Flow<String> = context.dataStore.data
        .map { it[Keys.SHEETS_URL] ?: BuildConfig.DEFAULT_SHEETS_URL }

    /** Epoch-ms of the last successful upload, or 0 if none yet — shown in Settings diagnostics. */
    val lastUploadTime: Flow<Long> = context.dataStore.data
        .map { it[Keys.LAST_UPLOAD] ?: 0L }

    /** Reason the most recent upload failed, or blank once an upload succeeds — shown in diagnostics. */
    val lastUploadError: Flow<String> = context.dataStore.data
        .map { it[Keys.LAST_UPLOAD_ERROR] ?: "" }

    suspend fun setSheetsUrl(url: String) {
        context.dataStore.edit { it[Keys.SHEETS_URL] = url }
    }

    suspend fun setLastUploadTime(epochMs: Long) {
        context.dataStore.edit { it[Keys.LAST_UPLOAD] = epochMs }
    }

    /** Record (or, with a blank string, clear) the last upload failure reason. */
    suspend fun setLastUploadError(reason: String) {
        context.dataStore.edit { it[Keys.LAST_UPLOAD_ERROR] = reason }
    }

    // --- In-app updater (Module 6) ---

    /** Whether the launch-time update check runs. Defaults to on. */
    val autoUpdateCheck: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.AUTO_UPDATE_CHECK] ?: true }

    /** versionCode the user chose to skip, so it won't re-prompt; 0 when none. */
    val skippedVersionCode: Flow<Long> = context.dataStore.data
        .map { it[Keys.SKIPPED_VERSION_CODE] ?: 0L }

    /** Epoch-ms of the last update check, or 0 if none yet — shown in diagnostics. */
    val lastUpdateCheck: Flow<Long> = context.dataStore.data
        .map { it[Keys.LAST_UPDATE_CHECK] ?: 0L }

    suspend fun setAutoUpdateCheck(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_UPDATE_CHECK] = enabled }
    }

    suspend fun setSkippedVersionCode(versionCode: Long) {
        context.dataStore.edit { it[Keys.SKIPPED_VERSION_CODE] = versionCode }
    }

    suspend fun setLastUpdateCheck(epochMs: Long) {
        context.dataStore.edit { it[Keys.LAST_UPDATE_CHECK] = epochMs }
    }
}
