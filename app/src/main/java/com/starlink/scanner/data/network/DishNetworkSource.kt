package com.starlink.scanner.data.network

import android.net.Network
import kotlinx.coroutines.flow.Flow

/**
 * Source of the dish WiFi [Network] (Module 2). The Capture ViewModel depends on this interface,
 * not on [android.net.ConnectivityManager], so its detection logic can be driven from a fake flow
 * in tests.
 *
 * Emissions:
 *  - a [Network] → the phone is on a WiFi network we can bind dish gRPC sockets to;
 *  - `null` → no such WiFi network (or it was just lost).
 */
interface DishNetworkSource {
    fun dishNetworkFlow(): Flow<Network?>
}
