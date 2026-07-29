package com.starlink.scanner.data.update

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Verifies UpdateChecker's tag parsing and status decisions against a mock Releases API. */
class UpdateCheckerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun checker() = UpdateChecker(
        repo = "owner/repo",
        apiBaseUrl = server.url("").toString().trimEnd('/'),
    )

    private fun releaseJson(tag: String, assetName: String? = "app-release.apk"): String {
        val asset = assetName?.let {
            """{"name":"$it","browser_download_url":"https://example/$it"}"""
        }.orEmpty()
        return """{"tag_name":"$tag","name":"Release $tag","body":"notes","assets":[$asset]}"""
    }

    @Test
    fun parseVersionCode_handlesTagShapes() {
        assertEquals(5L, UpdateChecker.parseVersionCode("v1.1+5"))
        assertEquals(12L, UpdateChecker.parseVersionCode("v2.0.3+12"))
        assertEquals(7L, UpdateChecker.parseVersionCode("7"))
        assertNull(UpdateChecker.parseVersionCode("v1.1"))
        assertNull(UpdateChecker.parseVersionCode(""))
    }

    @Test
    fun newerRelease_isAvailable() = runTest {
        server.enqueue(MockResponse().setBody(releaseJson("v1.1+5")))

        val status = checker().check(currentVersionCode = 4)

        assertTrue(status is UpdateStatus.Available)
        status as UpdateStatus.Available
        assertEquals(5L, status.versionCode)
        assertEquals("Release v1.1+5", status.versionName)
        assertEquals("notes", status.notes)
        assertEquals("https://example/app-release.apk", status.apkDownloadUrl)
    }

    @Test
    fun sameOrOlderRelease_isUpToDate() = runTest {
        server.enqueue(MockResponse().setBody(releaseJson("v1.0+3")))

        assertEquals(UpdateStatus.UpToDate, checker().check(currentVersionCode = 3))
    }

    @Test
    fun releaseWithoutApkAsset_isFailed() = runTest {
        server.enqueue(MockResponse().setBody(releaseJson("v1.1+5", assetName = null)))

        val status = checker().check(currentVersionCode = 1)
        assertTrue(status is UpdateStatus.Failed)
    }

    @Test
    fun malformedTag_isFailed() = runTest {
        server.enqueue(MockResponse().setBody(releaseJson("nightly")))

        val status = checker().check(currentVersionCode = 1)
        assertTrue(status is UpdateStatus.Failed)
    }

    @Test
    fun httpError_isFailed() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))

        val status = checker().check(currentVersionCode = 1)
        assertTrue(status is UpdateStatus.Failed)
        assertTrue((status as UpdateStatus.Failed).reason.contains("404"))
    }

    @Test
    fun unconfigured_isUpToDate() = runTest {
        val status = UpdateChecker(repo = "").check(currentVersionCode = 1)
        assertEquals(UpdateStatus.UpToDate, status)
    }
}
