package com.starlink.scanner.data.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
class StarlinkWifiConnector(private val cm: ConnectivityManager) {

    /** True on devices where app-initiated connect is available (API 29+). */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Start a connect request and emit the bound [Network] on success (null while not connected).
     * Collecting this triggers the system dialog; cancelling collection cancels the request.
     */
    fun connectFlow(): Flow<Network?> {
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

    companion object {
        private const val SSID_PREFIX = "STARLINK"
    }
}
