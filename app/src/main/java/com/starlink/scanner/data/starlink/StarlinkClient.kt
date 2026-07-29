package com.starlink.scanner.data.starlink

import android.net.Network
import com.starlink.scanner.domain.DishInfo
import com.starlink.scanner.domain.StarlinkError
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import spacex.api.device.DeviceGrpcKt
import spacex.api.device.GetDeviceInfoRequest
import spacex.api.device.GetStatusRequest
import spacex.api.device.Request
import spacex.api.device.Response
import java.io.Closeable
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Thin coroutine wrapper over the dish's unauthenticated plaintext gRPC API
 * (`SpaceX.API.Device.Device/Handle`) at 192.168.100.1:9200.
 *
 * Create one per detection session and [close] it when done — the channel is not reusable
 * across networks. All calls are `suspend`; never touch the main thread.
 */
class StarlinkClient internal constructor(private val channel: ManagedChannel) : Closeable {

    private val stub = DeviceGrpcKt.DeviceCoroutineStub(channel)

    /**
     * Read the dish ID via `get_status` (the rich call, which also carries identity), falling back
     * to `get_device_info` on firmware that doesn't implement `get_status`. Only the dish ID is
     * kept. Throws a typed [StarlinkError] — never a raw gRPC exception.
     */
    suspend fun fetchDeviceInfo(): DishInfo {
        val response = try {
            handle(Request.newBuilder().setGetStatus(GetStatusRequest.getDefaultInstance()).build())
        } catch (e: StarlinkError.Unimplemented) {
            // Firmware fallback: identity-only.
            handle(Request.newBuilder().setGetDeviceInfo(GetDeviceInfoRequest.getDefaultInstance()).build())
        }
        return DishInfo(dishId = response.dishId())
    }

    private suspend fun handle(request: Request): Response = try {
        withTimeout(TIMEOUT_MS) { stub.handle(request) }
    } catch (e: TimeoutCancellationException) {
        throw StarlinkError.Timeout(e)
    } catch (e: StatusException) {
        throw e.toStarlinkError()
    }

    override fun close() {
        channel.shutdownNow()
        channel.awaitTermination(1, TimeUnit.SECONDS)
    }

    companion object {
        private const val HOST = "192.168.100.1"
        private const val PORT = 9200
        private const val TIMEOUT_MS = 3_000L

        /** Build a client bound to [network] (null → default routing), talking plaintext to the dish. */
        fun forNetwork(network: Network? = null): StarlinkClient {
            val channel = OkHttpChannelBuilder
                .forAddress(HOST, PORT)
                .usePlaintext()
                .socketFactory(network?.socketFactory ?: SocketFactory.getDefault())
                .build()
            return StarlinkClient(channel)
        }
    }
}

/** Pull the dish ID out of whichever oneof arm the dish populated. */
private fun Response.dishId(): String = when (responseCase) {
    Response.ResponseCase.DISH_GET_STATUS -> dishGetStatus.deviceInfo.id
    Response.ResponseCase.GET_DEVICE_INFO -> getDeviceInfo.deviceInfo.id
    else -> throw StarlinkError.Unknown()
}

private fun StatusException.toStarlinkError(): StarlinkError = when (status.code) {
    Status.Code.UNAVAILABLE -> StarlinkError.Unavailable(this)
    Status.Code.DEADLINE_EXCEEDED -> StarlinkError.Timeout(this)
    Status.Code.UNIMPLEMENTED -> StarlinkError.Unimplemented(this)
    else -> StarlinkError.Unknown(this)
}
