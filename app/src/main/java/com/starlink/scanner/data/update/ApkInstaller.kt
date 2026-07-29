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

    /** Streams the APK at [downloadUrl] to `cacheDir/updates/update.apk` and returns the file. */
    suspend fun download(downloadUrl: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "update.apk")
        if (apk.exists()) apk.delete()

        val request = Request.Builder()
            .url(downloadUrl)
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed: HTTP ${resp.code}")
            val body = resp.body ?: error("Empty download body")
            apk.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        apk
    }

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
