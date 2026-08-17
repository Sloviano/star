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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

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
        val INSTALL_ID = stringPreferencesKey("install_id")
        val AUTO_JOIN_DISH_WIFI = booleanPreferencesKey("auto_join_dish_wifi")
        val DISH_SSID = stringPreferencesKey("dish_ssid")
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

    /**
     * Stable identifier for this install, generated on first use. Paired with a record's local row
     * id it forms the upload key that lets the backend recognize a re-sent record and refuse to
     * append it twice (see [com.starlink.scanner.data.upload.UploadRunner]). Row ids alone would
     * collide across installs, which all write into the same sheet.
     */
    suspend fun installId(): String {
        context.dataStore.data.first()[Keys.INSTALL_ID]?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        var result = generated
        // The edit block is atomic, so a concurrent generator either sees our value or we see theirs.
        context.dataStore.edit { prefs ->
            val existing = prefs[Keys.INSTALL_ID]
            if (existing.isNullOrBlank()) prefs[Keys.INSTALL_ID] = generated else result = existing
        }
        return result
    }

    // --- Device-wide dish WiFi (network suggestion) ---

    /**
     * Whether the dish AP is registered as a network suggestion, so the whole phone joins it and
     * other apps can reach the dish. Off by default: registering one makes Android show the user an
     * approval notification, which shouldn't happen to a technician who never asked for it.
     */
    val autoJoinDishWifi: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.AUTO_JOIN_DISH_WIFI] ?: false }

    /**
     * The dish AP's real SSID, learned from an app-initiated connection (see
     * [com.starlink.scanner.data.network.StarlinkWifiConnector.ssidOf]), or blank until one happens.
     * Remembered because a suggestion must name the SSID exactly and dish APs aren't all called
     * plain "STARLINK".
     */
    val dishSsid: Flow<String> = context.dataStore.data
        .map { it[Keys.DISH_SSID] ?: "" }

    suspend fun setAutoJoinDishWifi(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_JOIN_DISH_WIFI] = enabled }
    }

    suspend fun setDishSsid(ssid: String) {
        context.dataStore.edit { it[Keys.DISH_SSID] = ssid }
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
