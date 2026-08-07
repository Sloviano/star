package com.starlink.scanner.data.upload

import com.starlink.scanner.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Result of a single POST to the Apps Script endpoint. */
sealed interface UploadOutcome {
    /** HTTP 200 and the backend replied `{"status":"ok", ...}`. */
    data class Success(val count: Int) : UploadOutcome

    /** Anything else — non-2xx, unexpected body, or a transport error. [reason] is user-readable. */
    data class Failed(val reason: String) : UploadOutcome
}

/**
 * Client for the Google Apps Script Web App (`/exec`). Apps Script answers `/exec` with a 302 to
 * `script.googleusercontent.com` before the real 200, so redirects must be followed.
 *
 * Every request goes out through [post], which is private — the two public entry points build their
 * own body and always stamp [token] into it. That is deliberate: the endpoint is deployed
 * "Anyone"-access, so an unauthenticated request path would be a hole in the only thing guarding the
 * sheet.
 *
 * The call is wrapped in [suspendCancellableCoroutine] so it is a first-class `suspend` function and
 * cancels the in-flight request when the coroutine is cancelled (e.g. WorkManager stops the worker).
 */
class SheetsUploader(
    private val token: String = BuildConfig.SHEETS_TOKEN,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {

    /** POST a batch of records for appending. */
    suspend fun postBatch(url: String, records: List<ScanUploadDto>): UploadOutcome =
        post(url, json.encodeToString(ScanBatchDto.serializer(), ScanBatchDto(token, records)))

    /**
     * Body for Settings ▸ Test connection. The backend checks the secret and exercises the real
     * spreadsheet + tab resolution (the code path that actually fails in the field), then returns ok
     * without writing a row — so this also reports a token that doesn't match the deployment.
     */
    suspend fun postDryRun(url: String): UploadOutcome =
        post(url, json.encodeToString(DryRunDto.serializer(), DryRunDto(token)))

    private suspend fun post(url: String, body: String): UploadOutcome {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            return UploadOutcome.Failed(e.message ?: "Network error")
        }

        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return UploadOutcome.Failed("HTTP ${resp.code}")
            }
            val parsed = try {
                json.decodeFromString(SheetsResponse.serializer(), text)
            } catch (e: SerializationException) {
                return UploadOutcome.Failed("Unexpected response: ${text.take(120)}")
            }
            return if (parsed.status == "ok") {
                UploadOutcome.Success(parsed.count)
            } else {
                val detail = parsed.message.ifBlank { parsed.status.ifBlank { "unknown" } }
                UploadOutcome.Failed("Backend error: $detail")
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)          // Apps Script /exec replies 302 first.
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** Envelope returned by the Apps Script `doPost` handler. */
@kotlinx.serialization.Serializable
private data class SheetsResponse(val status: String = "", val count: Int = 0, val message: String = "")

/** Suspend bridge over OkHttp's async [Call.enqueue], cancelling the call on coroutine cancellation. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
