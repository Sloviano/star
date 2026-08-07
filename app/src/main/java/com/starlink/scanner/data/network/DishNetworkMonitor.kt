package com.starlink.scanner.data.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Real [DishNetworkSource] backed by [ConnectivityManager].
 *
 * The dish access point has **no internet**. By default Android marks such a network "no internet",
 * declines to keep our interest in it, and routes app traffic over mobile data — which makes
 * 192.168.100.1 unreachable. We work around this by requesting a WiFi transport with the INTERNET
 * capability *requirement removed*, then binding dish gRPC sockets to the resulting [Network] (done
 * in [com.starlink.scanner.data.starlink.StarlinkClient]). We do NOT call `bindProcessToNetwork`, so
 * the deferred Sheets upload stays free to use mobile data at the same time.
 *
 * SSID is never read (reachability of the gRPC endpoint is the real detection mechanism), so no
 * location permission is needed.
 *
 * This only *observes* WiFi the phone is already on, so it registers a callback rather than filing a
 * network request: `requestNetwork` asks the system to actively bring up and hold a matching
 * network, and this flow is collected for the entire life of the capture screen. Joining the dish AP
 * on purpose is [StarlinkWifiConnector]'s job, and that one does file a request.
 */
class DishNetworkMonitor(private val cm: ConnectivityManager) : DishNetworkSource {

    override fun dishNetworkFlow(): Flow<Network?> = callbackFlow {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // Key line: accept WiFi networks that have no internet (the dish AP).
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // The network we last emitted. Only touched from ConnectivityManager's callback thread,
        // which delivers these serially.
        var reported: Network? = null

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                reported = network
                trySend(network)
            }

            override fun onLost(network: Network) {
                // Report the loss only for the network we handed out. A different WiFi network
                // going away must not unbind dish sockets that are working fine.
                if (reported == network) {
                    reported = null
                    trySend(null)
                }
            }
        }

        cm.registerNetworkCallback(request, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.conflate()
}
