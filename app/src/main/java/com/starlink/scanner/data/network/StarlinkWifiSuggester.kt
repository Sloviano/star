package com.starlink.scanner.data.network

import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import androidx.annotation.RequiresApi

/** Outcome of registering (or withdrawing) the dish AP suggestion. */
sealed interface SuggestResult {
    /** The suggestion is registered. Android may still ask the user to approve it (see below). */
    data object Registered : SuggestResult

    /** The suggestion is no longer registered. */
    data object Withdrawn : SuggestResult

    /** Device is below API 29, or has no WiFi service. */
    data object Unsupported : SuggestResult

    /** Android rejected the request. [reason] is user-readable. */
    data class Failed(val reason: String) : SuggestResult
}

/**
 * Registers the dish access point as a **network suggestion**, so Android joins it *device-wide* and
 * every app on the phone can reach 192.168.100.1 — not just this one.
 *
 * This is the counterpart to [StarlinkWifiConnector], and the difference is the whole point:
 *
 *  - [StarlinkWifiConnector] uses `WifiNetworkSpecifier`, which hands **this app alone** a private
 *    connection. Other apps cannot see or use it. Instant and reliable, but app-scoped by design.
 *  - This class uses `WifiNetworkSuggestion`, which asks the platform to treat the AP as a network
 *    the phone may join normally. Once it does, the connection is ordinary and shared by everything.
 *
 * The trade is control. A suggestion is a *hint*: the platform decides whether and when to act on it,
 * the first match raises a notification asking the user to allow this app's suggestions, and a
 * declined app gets [WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED] thereafter. So
 * [register] returning [SuggestResult.Registered] means "Android accepted the suggestion", never
 * "the phone is now on the dish WiFi". Suggestions persist across reboots until withdrawn or the app
 * is uninstalled, which is why this is a Settings toggle rather than a capture-screen button.
 *
 * **Exact SSIDs only.** `WifiNetworkSuggestion.Builder` has no `setSsidPattern` at any API level, so
 * unlike the prefix-matching specifier this must name the AP exactly. [StarlinkWifiConnector.ssidOf]
 * learns the real SSID from a previous app-initiated connection; until it has, only the stock
 * "STARLINK" is suggested. That is why [register] takes a list — the learned SSID *and* the default.
 *
 * Requires `CHANGE_WIFI_STATE` (install-time). Notably not location: suggestions don't need it.
 */
class StarlinkWifiSuggester(private val wifiManager: WifiManager?) {

    /** True where suggestions exist (API 29+) and the WiFi service is present. */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && wifiManager != null

    /**
     * Register [ssids] (open networks) as suggestions, replacing anything registered earlier so a
     * newly-learned SSID doesn't accumulate alongside stale ones.
     */
    fun register(ssids: List<String>): SuggestResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return SuggestResult.Unsupported
        val wifi = wifiManager ?: return SuggestResult.Unsupported
        val wanted = ssids.map(String::trim).filter(String::isNotEmpty).distinct()
        if (wanted.isEmpty()) return SuggestResult.Failed("No Starlink SSID to suggest")
        return registerOnQ(wifi, wanted)
    }

    /** Withdraw every suggestion this app registered. */
    fun withdraw(): SuggestResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return SuggestResult.Unsupported
        val wifi = wifiManager ?: return SuggestResult.Unsupported
        return withdrawOnQ(wifi)
    }

    // Everything below touches API 29+ WiFi-suggestion APIs. The public entry points above do the
    // version check, and these carry @RequiresApi so that contract is checked rather than assumed.

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerOnQ(wifi: WifiManager, ssids: List<String>): SuggestResult = try {
        // Clear first: addNetworkSuggestions merges into the existing set, so without this a changed
        // SSID list would leave the old entries registered forever.
        clearAll(wifi)
        describeAdd(wifi.addNetworkSuggestions(ssids.map(::suggestionFor)))
    } catch (e: SecurityException) {
        SuggestResult.Failed(e.message ?: "Not allowed to suggest WiFi networks")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun withdrawOnQ(wifi: WifiManager): SuggestResult = try {
        clearAll(wifi)
        SuggestResult.Withdrawn
    } catch (e: SecurityException) {
        SuggestResult.Failed(e.message ?: "Not allowed to modify WiFi suggestions")
    }

    /**
     * Remove everything this app has registered. `getNetworkSuggestions()` (API 30+) reports them
     * back exactly, so prefer it; on API 29 the best available is to remove the SSIDs we would have
     * registered ourselves.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun clearAll(wifi: WifiManager) {
        val existing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wifi.networkSuggestions
        } else {
            DEFAULT_SSIDS.map(::suggestionFor)
        }
        if (existing.isNotEmpty()) wifi.removeNetworkSuggestions(existing)
    }

    /** An open-network suggestion for [ssid] — the dish AP has no passphrase. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun suggestionFor(ssid: String): WifiNetworkSuggestion =
        WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            // The dish AP has no internet, so don't let the platform hold this against it or bill
            // the technician's data plan for it.
            .setIsMetered(false)
            .build()

    companion object {
        /** Fallback when no SSID has been learned yet: a stock dish AP is named exactly this. */
        val DEFAULT_SSIDS = listOf(StarlinkWifiConnector.SSID_PREFIX)

        /**
         * Map `addNetworkSuggestions`'s status int onto a result.
         *
         * `ERROR_ADD_DUPLICATE` counts as success: it means the suggestion is already registered,
         * which is precisely the state the caller asked for. Only the API 29 constants are named
         * here — the 30+/33+ codes report their raw value rather than forcing an inlined-constant
         * reference below this class's floor.
         */
        fun describeAdd(status: Int): SuggestResult = when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS,
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE,
            -> SuggestResult.Registered

            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED -> SuggestResult.Failed(
                "You declined WiFi suggestions from this app. Re-allow them in Android Settings ▸ " +
                    "Apps ▸ Starlink Scanner ▸ WiFi control."
            )

            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP ->
                SuggestResult.Failed("Too many WiFi suggestions registered on this device")

            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL ->
                SuggestResult.Failed("Android rejected the suggestion (internal error)")

            else -> SuggestResult.Failed("WiFi suggestion failed (code $status)")
        }
    }
}
