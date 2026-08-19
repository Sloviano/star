package com.starlink.scanner.data.settings

import com.starlink.scanner.domain.ScanMode
import kotlinx.coroutines.flow.Flow

/*
 * Narrow views of [SettingsRepository], one per consumer.
 *
 * The repository itself is backed by DataStore and needs an [android.content.Context], so anything
 * depending on the whole class can only be exercised on a device. These slices follow the pattern
 * the rest of the app already uses at platform boundaries ([com.starlink.scanner.data.network
 * .DishNetworkSource], [com.starlink.scanner.data.network.DishReachability],
 * [com.starlink.scanner.data.starlink.StarlinkRepository]): consumers depend on the few members
 * they actually use, and a JVM test supplies a plain in-memory implementation.
 *
 * Each is deliberately small. Screens that genuinely touch most of the settings surface — Settings
 * itself — keep depending on [SettingsRepository] directly.
 */

/** What the upload path reads and records. Implemented by [SettingsRepository]. */
interface UploadSettings {
    /** Apps Script `/exec` endpoint, or the baked-in default when the user hasn't set one. */
    val sheetsUrl: Flow<String>

    suspend fun setLastUploadTime(epochMs: Long)

    /** Record (or, with a blank string, clear) the last upload failure reason. */
    suspend fun setLastUploadError(reason: String)
}

/** What the capture flow reads and records. Implemented by [SettingsRepository]. */
interface CaptureSettings {
    /** The number the next saved record will carry into the sheet's first column. */
    val nextCounter: Flow<Long>

    /** How the kit number is captured — Data Matrix label, or OCR of the printed text. */
    val scanMode: Flow<ScanMode>

    /** The dish AP's learned SSID, or blank until an app-initiated connect has taught us one. */
    val dishSsid: Flow<String>

    /** Claim the next counter value and advance the stored one, atomically. */
    suspend fun takeNextCounter(): Long

    suspend fun setScanMode(mode: ScanMode)

    suspend fun setDishSsid(ssid: String)
}
