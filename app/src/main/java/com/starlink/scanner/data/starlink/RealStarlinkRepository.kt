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
 */
class RealStarlinkRepository : StarlinkRepository {

    override suspend fun getDishInfo(network: Network?): Result<DishInfo> {
        val client = StarlinkClient.forNetwork(network)
        return try {
            Result.success(withContext(Dispatchers.IO) { client.fetchDeviceInfo() })
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
