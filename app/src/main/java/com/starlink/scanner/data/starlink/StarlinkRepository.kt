package com.starlink.scanner.data.starlink

import android.net.Network
import com.starlink.scanner.domain.DishInfo

/**
 * Abstraction over the dish gRPC client (Module 1). The Capture ViewModel depends on this
 * interface, not on gRPC directly, which keeps the capture flow decoupled from the transport.
 * The only implementation is [RealStarlinkRepository].
 */
interface StarlinkRepository {
    /**
     * Fetch the dish's identity + status over the given [network] (the WiFi network bound to the
     * dish, or null for default routing). Returns [Result.failure] with a
     * [com.starlink.scanner.domain.StarlinkError] on any failure — never throws.
     */
    suspend fun getDishInfo(network: Network?): Result<DishInfo>
}
