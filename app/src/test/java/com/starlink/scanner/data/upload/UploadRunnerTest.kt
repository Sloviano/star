package com.starlink.scanner.data.upload

import com.starlink.scanner.data.local.ScanRecord
import com.starlink.scanner.domain.UploadStatus
import com.starlink.scanner.fakes.FakeScanDao
import com.starlink.scanner.fakes.FakeUploadSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Exercises the batched upload state machine against a real [SheetsUploader] pointed at a
 * [MockWebServer], so the JSON the backend actually receives is part of what these assert.
 */
class UploadRunnerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = server.url("/exec").toString()

    private fun record(id: Long, kit: String, status: UploadStatus = UploadStatus.PENDING) = ScanRecord(
        id = id,
        counter = id,
        timestamp = 1_700_000_000_000 + id,
        dishId = "01000000-00000000-0000000$id",
        kitNumber = kit,
        dishSerial = "DISH-$id",
        status = status,
    )

    private fun ok(count: Int) = MockResponse().setBody("""{"status":"ok","count":$count}""")

    @Test
    fun run_withNothingPending_reportsNothingPendingAndPostsNothing() = runTest {
        val dao = FakeScanDao()
        val runner = UploadRunner(dao, FakeUploadSettings(url()), SheetsUploader(token = "t"))

        assertEquals(SyncResult.NothingPending, runner.run())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun run_withoutAConfiguredUrl_reportsNoUrlAndLeavesRecordsPending() = runTest {
        val dao = FakeScanDao(listOf(record(1, "KIT-1")))
        val runner = UploadRunner(dao, FakeUploadSettings(url = ""), SheetsUploader(token = "t"))

        assertEquals(SyncResult.NoUrl, runner.run())
        assertEquals(0, server.requestCount)
        // Nothing was marked failed either — an unconfigured endpoint isn't an upload attempt.
        assertTrue(dao.failedBatches.isEmpty())
        assertEquals(1, dao.pending().size)
    }

    @Test
    fun run_onSuccess_marksTheWholeBatchSentAndRecordsTheTime() = runTest {
        val dao = FakeScanDao(listOf(record(1, "KIT-1"), record(2, "KIT-2", UploadStatus.FAILED)))
        val settings = FakeUploadSettings(url())
        server.enqueue(ok(2))

        val result = UploadRunner(dao, settings, SheetsUploader(token = "secret")).run()

        assertEquals(SyncResult.Sent(2), result)
        // FAILED records are retried alongside PENDING ones — both go in the one batch.
        assertEquals(listOf(listOf(1L, 2L)), dao.sentBatches)
        assertTrue(dao.pending().isEmpty())
        assertTrue(settings.lastUploadTime > 0)
        assertEquals("", settings.lastUploadError)

        val body = server.takeRequest().body.readUtf8()
        assertTrue("token must travel in the body: $body", body.contains(""""token":"secret""""))
        assertTrue(body.contains(""""kitNumber":"KIT-1""""))
        assertTrue(body.contains(""""kitNumber":"KIT-2""""))
    }

    @Test
    fun run_onBackendError_marksTheBatchFailedAndKeepsTheReason() = runTest {
        val dao = FakeScanDao(listOf(record(1, "KIT-1")))
        val settings = FakeUploadSettings(url())
        server.enqueue(MockResponse().setBody("""{"status":"error","message":"Unauthorized"}"""))

        val result = UploadRunner(dao, settings, SheetsUploader(token = "wrong")).run()

        assertEquals(SyncResult.Failed("Backend error: Unauthorized"), result)
        assertEquals(listOf(listOf(1L)), dao.failedBatches)
        // Still pending: a rejected batch is retried, never dropped.
        assertEquals(1, dao.pending().size)
        assertEquals("Backend error: Unauthorized", settings.lastUploadError)
        assertEquals(0L, settings.lastUploadTime)
    }

    @Test
    fun run_onHttpError_reportsTheStatusCode() = runTest {
        val dao = FakeScanDao(listOf(record(1, "KIT-1")))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = UploadRunner(dao, FakeUploadSettings(url()), SheetsUploader(token = "t")).run()

        assertEquals(SyncResult.Failed("HTTP 500"), result)
    }

    /**
     * Regression: the WorkManager job and History's "Sync now" share one [UploadRunner] instance in
     * one process, and "Sync now" deliberately bypasses WorkManager. Before [UploadRunner] took a
     * lock, both could read the same pending list and POST it, appending every row to the sheet
     * twice — with no network failure involved. The second caller must instead wait, re-read, and
     * find the batch already settled.
     *
     * The response is delayed so the two calls genuinely overlap rather than lining up by luck.
     */
    @Test
    fun run_concurrently_postsTheBatchOnlyOnce() = runTest {
        val dao = FakeScanDao(listOf(record(1, "KIT-1"), record(2, "KIT-2")))
        val runner = UploadRunner(dao, FakeUploadSettings(url()), SheetsUploader(token = "t"))
        server.enqueue(ok(2).setBodyDelay(300, TimeUnit.MILLISECONDS))
        server.enqueue(ok(2)) // Only served if the lock fails to hold.

        val results = listOf(
            async { runner.run() },
            async { runner.run() },
        ).awaitAll()

        assertEquals("the batch must be POSTed exactly once", 1, server.requestCount)
        assertEquals(listOf(listOf(1L, 2L)), dao.sentBatches)
        assertTrue(results.contains(SyncResult.Sent(2)))
        assertTrue("the second caller must find nothing left", results.contains(SyncResult.NothingPending))
    }
}
