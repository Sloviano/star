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
import com.starlink.scanner.domain.ScanMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** App settings backed by DataStore (Preferences): Apps Script URL and upload diagnostics. */
class SettingsRepository(private val context: Context) : UploadSettings, CaptureSettings {

    private object Keys {
        val SHEETS_URL = stringPreferencesKey("sheets_url")
        val LAST_UPLOAD = longPreferencesKey("last_upload")
        val LAST_UPLOAD_ERROR = stringPreferencesKey("last_upload_error")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val SKIPPED_VERSION_CODE = longPreferencesKey("skipped_version_code")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val AUTO_JOIN_DISH_WIFI = booleanPreferencesKey("auto_join_dish_wifi")
        val DISH_SSID = stringPreferencesKey("dish_ssid")
        val NEXT_COUNTER = longPreferencesKey("next_counter")
        val SCAN_MODE = stringPreferencesKey("scan_mode")
    }

    override val sheetsUrl: Flow<String> = context.dataStore.data
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

    override suspend fun setLastUploadTime(epochMs: Long) {
        context.dataStore.edit { it[Keys.LAST_UPLOAD] = epochMs }
    }

    /** Record (or, with a blank string, clear) the last upload failure reason. */
    override suspend fun setLastUploadError(reason: String) {
        context.dataStore.edit { it[Keys.LAST_UPLOAD_ERROR] = reason }
    }

    // --- Sheet row counter ---

    /**
     * The number the next saved record will carry into the sheet's first column. Settable to any
     * starting value in Settings so a technician can continue a paper log or split a range across
     * two phones; from there it advances by one per saved record.
     */
    override val nextCounter: Flow<Long> = context.dataStore.data
        .map { it[Keys.NEXT_COUNTER] ?: DEFAULT_COUNTER }

    /** Set the number the next saved record will use. */
    suspend fun setNextCounter(value: Long) {
        context.dataStore.edit { it[Keys.NEXT_COUNTER] = value }
    }

    /**
     * Claim the next counter value and advance the stored one, in a single atomic edit so two saves
     * racing (a fast double-tap, or the screen restoring mid-save) can never hand out the same
     * number twice. Consumed at save time only — a record the technician discards from the summary
     * doesn't burn a number.
     */
    override suspend fun takeNextCounter(): Long {
        var claimed = DEFAULT_COUNTER
        context.dataStore.edit { prefs ->
            claimed = prefs[Keys.NEXT_COUNTER] ?: DEFAULT_COUNTER
            prefs[Keys.NEXT_COUNTER] = claimed + 1
        }
        return claimed
    }

    // --- Kit capture mode ---

    /**
     * How the kit number is captured. Persisted rather than reset per kit: a technician working a
     * pallet of boxes with unreadable labels shouldn't re-pick text mode on every one, and the
     * toggle is one tap away on the capture screen when they want the barcode back.
     *
     * An unrecognised stored value falls back to [ScanMode.BARCODE] — the mode that always works.
     */
    override val scanMode: Flow<ScanMode> = context.dataStore.data
        .map { prefs ->
            prefs[Keys.SCAN_MODE]
                ?.let { name -> ScanMode.entries.firstOrNull { it.name == name } }
                ?: ScanMode.BARCODE
        }

    override suspend fun setScanMode(mode: ScanMode) {
        context.dataStore.edit { it[Keys.SCAN_MODE] = mode.name }
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
    override val dishSsid: Flow<String> = context.dataStore.data
        .map { it[Keys.DISH_SSID] ?: "" }

    suspend fun setAutoJoinDishWifi(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_JOIN_DISH_WIFI] = enabled }
    }

    override suspend fun setDishSsid(ssid: String) {
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

    companion object {
        /** Where the sheet counter starts before anyone sets it. */
        const val DEFAULT_COUNTER = 1L
    }
}
