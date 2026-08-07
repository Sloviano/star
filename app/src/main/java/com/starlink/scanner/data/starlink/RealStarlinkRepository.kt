package com.starlink.scanner.data.starlink

import android.net.Network
import com.starlink.scanner.domain.DishInfo
import com.starlink.scanner.domain.StarlinkError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real gRPC-backed repository: opens a fresh [StarlinkClient] bound to [network], fetches dish
 * identity, and always closes the channel. Failures come back as [Result.failure] carrying a
 * [StarlinkError]; coroutine cancellation is propagated, not swallowed.
 *
 * The *whole* method runs on [Dispatchers.IO], not just the RPC: building the channel and
 * [StarlinkClient.close] are both blocking (close awaits channel termination for up to a second),
 * and callers suspend from the main dispatcher — the capture screen polls this every few seconds.
 */
class RealStarlinkRepository : StarlinkRepository {

    override suspend fun getDishInfo(network: Network?): Result<DishInfo> = withContext(Dispatchers.IO) {
        val client = StarlinkClient.forNetwork(network)
        try {
            Result.success(client.fetchDeviceInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: StarlinkError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(StarlinkError.Unknown(e))
        } finally {
            runCatching { client.close() }
        }
    }
}
