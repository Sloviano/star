package com.starlink.scanner.data.network

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.InetSocketAddress
import javax.net.SocketFactory

/**
 * Cheap "are we actually on the dish's LAN?" check (Module 2). Being on *some* WiFi is not enough —
 * the phone could be on home/office WiFi. The reliable signal is that the dish's gRPC endpoint at
 * 192.168.100.1:9200 answers a TCP connect on the given [network].
 *
 * This is deliberately lighter than a full gRPC `get_status` call: it just opens a socket and
 * closes it, so the UI can flip between "not connected to Starlink" and "dish found" quickly and
 * only pay for the heavier gRPC read once the endpoint is up.
 */
interface DishReachability {
    /** True if a TCP connection to the dish endpoint over [network] succeeds within the timeout. */
    suspend fun isReachable(network: Network?): Boolean
}

/** Real probe: binds the socket to [network] (so it doesn't leak onto mobile data) and connects. */
class TcpDishReachability : DishReachability {

    override suspend fun isReachable(network: Network?): Boolean {
        // No bound WiFi network → we can't be on the dish LAN.
        network ?: return false
        val socketFactory: SocketFactory = network.socketFactory
        return runInterruptible(Dispatchers.IO) {
            runCatching {
                socketFactory.createSocket().use { socket ->
                    socket.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
                }
                true
            }.getOrDefault(false)
        }
    }

    private companion object {
        const val HOST = "192.168.100.1"
        const val PORT = 9200
        const val CONNECT_TIMEOUT_MS = 1_500
    }
}
