package app.onedown.core.engine

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resumeWithException

internal class DownloadHttp(private val client: OkHttpClient) {
    suspend fun execute(input: DownloadRequest, method: String, range: String? = null, validator: String? = null): Response {
        var url = input.url
        val redirectClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        repeat(21) { hop ->
            val response = executeOnce(redirectClient, input, url, method, range, validator)
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val next = response.header("Location")?.let { response.request.url.resolve(it) }
            response.close()
            if (next == null) throw DownloadError.Unknown("Redirect missing a valid Location")
            if (hop == 20) throw DownloadError.Unknown("Too many redirects")
            if (url.startsWith("https:") && next.scheme == "http") throw DownloadError.Unknown("Insecure redirect rejected")
            url = next.toString()
        }
        throw DownloadError.Unknown("Redirect limit exceeded")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun executeOnce(client: OkHttpClient, input: DownloadRequest, url: String, method: String, range: String?, validator: String?): Response {
        val builder = Request.Builder().url(url)
        input.headers.forEach { (name, value) -> builder.header(name, value) }
        if (input.headers.keys.none { it.equals("Accept-Encoding", true) }) builder.header("Accept-Encoding", "identity")
        if (input.headers.keys.none { it.equals("Referer", true) }) {
            input.referrerPageUrl?.let { builder.header("Referer", it) }
        }
        if (range != null) builder.header("Range", range)
        if (validator != null) builder.header("If-Range", validator)
        val request = builder.method(method, null).build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
    }
}

internal fun checkHttp(code: Int) {
    when (code) {
        403 -> throw DownloadError.Forbidden()
        404 -> throw DownloadError.NotFound()
        410 -> throw DownloadError.LinkExpired()
        416 -> throw DownloadError.RangeNotSatisfiable()
        in 500..599, 408, 429 -> throw IOException("Retryable HTTP $code")
        !in 200..299 -> throw DownloadError.Unknown("HTTP $code")
    }
}