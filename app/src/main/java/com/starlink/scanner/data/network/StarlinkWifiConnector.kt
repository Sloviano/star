package com.starlink.scanner.data.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow

/**
 * Actively joins the dish's open "STARLINK…" access point via a [WifiNetworkSpecifier] request.
 *
 * Android does not allow an app to silently join a WiFi network: [ConnectivityManager.requestNetwork]
 * with a specifier shows a one-time system chooser/approval dialog, and on approval the returned
 * [Network] is bound to this request — which is exactly what
 * [com.starlink.scanner.data.starlink.StarlinkClient] binds its gRPC sockets to. The connection lives
 * only as long as the flow is collected; leaving the screen tears it down.
 *
 * Requires API 29+ (the specifier API). On older devices this is a no-op empty flow and the
 * technician connects to the dish WiFi manually, as before.
 */
class StarlinkWifiConnector(private val cm: ConnectivityManager) : DishWifiConnector {

    /** True on devices where app-initiated connect is available (API 29+). */
    override val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Start a connect request and emit the bound [Network] on success (null while not connected).
     * Collecting this triggers the system dialog; cancelling collection cancels the request.
     */
    override fun connectFlow(): Flow<Network?> {
        if (!isSupported) return emptyFlow()
        return callbackFlow {
            val specifier = WifiNetworkSpecifier.Builder()
                // Prefix match so "STARLINK", "STARLINK-1234", etc. all qualify. Open network → no
                // passphrase set.
                .setSsidPattern(PatternMatcher(SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // The dish AP has no internet; don't require it or the request never resolves.
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(network)
                }

                override fun onLost(network: Network) {
                    trySend(null)
                }

                override fun onUnavailable() {
                    trySend(null)
                }
            }

            cm.requestNetwork(request, callback)
            awaitClose { cm.unregisterNetworkCallback(callback) }
        }.conflate()
    }

    /**
     * The SSID the dish AP actually answered on, read back from a [connectFlow] network — or null if
     * it can't be determined.
     *
     * Worth the trouble because [StarlinkWifiSuggester] can only target an **exact** SSID, while this
     * class matches a prefix: the only way to suggest the technician's real AP ("STARLINK-1234") and
     * not just the bare default is to observe what we connected to and remember it.
     *
     * Reading an SSID normally costs `ACCESS_FINE_LOCATION`, which this app deliberately never asks
     * for — and the redacted value comes back as `<unknown ssid>`, which [normalizeSsid] rejects.
     * The exception is a network *this app itself* brought up with a [WifiNetworkSpecifier]: the
     * platform hands the requesting app the unredacted [WifiInfo], since it already knows what it
     * asked for. So pass only networks from [connectFlow] here; a passively-observed WiFi network
     * (the technician joining from system settings) will simply return null.
     */
    override fun ssidOf(network: Network): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val info = caps.transportInfo as? WifiInfo ?: return null
        return normalizeSsid(info.ssid)
    }

    companion object {
        /**
         * Default SSID prefix of the dish AP. Also the exact SSID of a stock unit, which is why
         * [StarlinkWifiSuggester] can fall back to it before anything has been learned.
         */
        const val SSID_PREFIX = "STARLINK"

        /**
         * What [WifiInfo.getSSID] returns instead of a name when the platform redacts it. Spelled out
         * rather than referencing `WifiManager.UNKNOWN_SSID`, which is API 30 — above this class's
         * API 29 floor.
         */
        private const val UNKNOWN_SSID = "<unknown ssid>"

        /**
         * Clean up a raw [WifiInfo.getSSID] value: it arrives wrapped in quotes (`"STARLINK"`), and
         * is the literal `<unknown ssid>` when the platform redacts it for want of location
         * permission. Returns null for anything unusable so callers never suggest a junk SSID.
         */
        fun normalizeSsid(raw: String?): String? {
            val trimmed = raw?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
            return trimmed.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
        }
    }
}
