package com.starlink.scanner.data.network

import android.net.Network
import kotlinx.coroutines.flow.Flow

/**
 * App-initiated join of the dish's access point (Module 2). The Capture ViewModel depends on this
 * interface, not on [StarlinkWifiConnector], so its connect-and-remember-the-SSID logic can be
 * driven from a fake in tests — the real one needs a [android.net.ConnectivityManager].
 *
 * See [StarlinkWifiConnector] for what the platform actually does here, and how this differs from
 * the device-wide [StarlinkWifiSuggester].
 */
interface DishWifiConnector {

    /** True on devices where app-initiated connect is available (API 29+). */
    val isSupported: Boolean

    /**
     * Start a connect request and emit the bound [Network] on success, `null` while not connected.
     * Collecting triggers the system approval dialog; cancelling collection tears the request down.
     */
    fun connectFlow(): Flow<Network?>

    /** The SSID [network] answered on, or null when it can't be read. Pass only [connectFlow] networks. */
    fun ssidOf(network: Network): String?
}
