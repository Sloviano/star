package com.starlink.scanner.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads an update APK from a public GitHub release asset and hands it to the system package
 * installer. The download URL is public (the releases repo is public), so no auth is sent; GitHub
 * 302-redirects to a CDN URL, which OkHttp follows automatically.
 */
class ApkInstaller(
    private val context: Context,
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * Streams the APK at [downloadUrl] to `cacheDir/updates/update.apk` and returns the file.
     * [onProgress] is invoked with 0..100 as bytes arrive (only when the server reports a total
     * size via Content-Length); it is never called for an unknown-length response.
     */
    suspend fun download(downloadUrl: String, onProgress: (Int) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val dir = updatesDir().apply { mkdirs() }
            val apk = File(dir, "update.apk")
            if (apk.exists()) apk.delete()

            val request = Request.Builder().url(downloadUrl).build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("Download failed: HTTP ${resp.code}")
                val body = resp.body ?: error("Empty download body")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    apk.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                        }
                    }
                }
            }
            apk
        }

    /**
     * Delete any APK left in the cache by an earlier update. The download is tens of megabytes and
     * nothing removes it once the install is done, so it would otherwise sit on the device until
     * some later update happened to overwrite it.
     *
     * Only call this when no install can still be pending: the system installer reads the file
     * through the FileProvider *after* [install] returns, so deleting it while the user is still
     * looking at the installer would break that install. See the call site in
     * [com.starlink.scanner.ui.update.UpdateViewModel].
     */
    suspend fun clearDownloads() = withContext(Dispatchers.IO) {
        updatesDir().listFiles()?.forEach { it.delete() }
        Unit
    }

    private fun updatesDir(): File = File(context.cacheDir, "updates")

    /** API 26+: whether the app may currently request package installs. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Send the user to system settings to allow installs from this app. */
    fun promptEnableUnknownSources() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Launch the system installer for [apk] via a FileProvider content:// URI. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS) // APK is multi-MB.
            .build()
    }
}
