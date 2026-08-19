package com.starlink.scanner.fakes

import android.net.Network
import com.starlink.scanner.data.local.ScanDao
import com.starlink.scanner.data.local.ScanRecord
import com.starlink.scanner.data.network.DishNetworkSource
import com.starlink.scanner.data.network.DishReachability
import com.starlink.scanner.data.network.DishWifiConnector
import com.starlink.scanner.data.settings.CaptureSettings
import com.starlink.scanner.data.settings.UploadSettings
import com.starlink.scanner.data.starlink.StarlinkRepository
import com.starlink.scanner.domain.DishInfo
import com.starlink.scanner.domain.ScanMode
import com.starlink.scanner.domain.UploadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory doubles for the interfaces the app puts at its platform boundaries.
 *
 * They exist because the real implementations need a [android.content.Context], a
 * [android.net.ConnectivityManager] or a live dish. Note that none of them can hand out a real
 * [Network]: it is a final platform class and the JVM test classpath only has the stub android.jar,
 * so every fake here treats the network as opaque and ignores it. Tests therefore drive the
 * "no network at all" and "reachable" cases; telling NO_WIFI from NO_DISH needs an instrumented
 * test, since that branch turns on a non-null Network.
 */

/** [ScanDao] backed by a list, with just enough behaviour to exercise the upload state machine. */
class FakeScanDao(initial: List<ScanRecord> = emptyList()) : ScanDao {

    val records = MutableStateFlow(initial)

    /** Ids passed to [markSent], in call order — one entry per settled batch. */
    val sentBatches = mutableListOf<List<Long>>()

    /** Ids passed to [markFailed], in call order. */
    val failedBatches = mutableListOf<List<Long>>()

    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    override suspend fun insert(record: ScanRecord): Long {
        val id = nextId++
        records.value = records.value + record.copy(id = id)
        return id
    }

    override fun observeAll(): Flow<List<ScanRecord>> =
        records.map { list -> list.sortedByDescending { it.timestamp } }

    override suspend fun getById(id: Long): ScanRecord? = records.value.firstOrNull { it.id == id }

    override suspend fun pending(): List<ScanRecord> =
        records.value.filter { it.status != UploadStatus.SENT }.sortedBy { it.timestamp }

    override fun pendingCount(): Flow<Int> =
        records.map { list -> list.count { it.status != UploadStatus.SENT } }

    override suspend fun countMatching(dishId: String, kitNumber: String): Int =
        records.value.count { it.dishId == dishId && it.kitNumber == kitNumber }

    override suspend fun markSent(ids: List<Long>) {
        sentBatches += ids
        records.value = records.value.map {
            if (it.id in ids) it.copy(status = UploadStatus.SENT) else it
        }
    }

    override suspend fun markFailed(ids: List<Long>) {
        failedBatches += ids
        records.value = records.value.map {
            if (it.id in ids) it.copy(status = UploadStatus.FAILED, attempts = it.attempts + 1) else it
        }
    }
}

/** [UploadSettings] holding the endpoint and the diagnostics the runner writes back. */
class FakeUploadSettings(url: String = "") : UploadSettings {
    override val sheetsUrl = MutableStateFlow(url)
    var lastUploadTime: Long = 0L
        private set
    var lastUploadError: String = ""
        private set

    override suspend fun setLastUploadTime(epochMs: Long) { lastUploadTime = epochMs }
    override suspend fun setLastUploadError(reason: String) { lastUploadError = reason }
}

/** [CaptureSettings] with an in-memory counter that advances exactly like the real atomic claim. */
class FakeCaptureSettings(
    counter: Long = 1L,
    ssid: String = "",
    mode: ScanMode = ScanMode.BARCODE,
) : CaptureSettings {
    override val nextCounter = MutableStateFlow(counter)
    override val dishSsid = MutableStateFlow(ssid)
    override val scanMode = MutableStateFlow(mode)

    override suspend fun takeNextCounter(): Long = nextCounter.value.also { nextCounter.value = it + 1 }
    override suspend fun setDishSsid(ssid: String) { dishSsid.value = ssid }
    override suspend fun setScanMode(mode: ScanMode) { scanMode.value = mode }
}

/** [StarlinkRepository] returning a scripted result and counting how often it was asked. */
class FakeStarlinkRepository(
    var result: Result<DishInfo> = Result.success(DishInfo("ut01000000-00000000-00001234")),
) : StarlinkRepository {
    var calls = 0
        private set

    override suspend fun getDishInfo(network: Network?): Result<DishInfo> {
        calls++
        return result
    }
}

/** [DishReachability] whose answer the test flips between polls. */
class FakeDishReachability(reachable: Boolean = false) : DishReachability {
    var reachable: Boolean = reachable
    var calls = 0
        private set

    override suspend fun isReachable(network: Network?): Boolean {
        calls++
        return reachable
    }
}

/** [DishNetworkSource] that never reports a network — the only case a JVM test can express. */
class FakeDishNetworkSource : DishNetworkSource {
    val networks = MutableStateFlow<Network?>(null)
    override fun dishNetworkFlow(): Flow<Network?> = networks
}

/** [DishWifiConnector] that reports the platform as unsupported unless a test says otherwise. */
class FakeDishWifiConnector(override val isSupported: Boolean = false) : DishWifiConnector {
    val networks = MutableStateFlow<Network?>(null)
    var connectFlowCollected = 0
        private set

    override fun connectFlow(): Flow<Network?> {
        connectFlowCollected++
        return networks
    }

    override fun ssidOf(network: Network): String? = null
}
