package com.alal.downloader.feature.downloads

import androidx.lifecycle.ViewModel
import com.alal.downloader.core.engine.*
import java.io.File
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Injectable probe boundary shared by Connect and the transfer engine. */
fun interface DownloadProber {
    suspend fun probe(request: DownloadRequest): ProbeResult
}

/** Form metadata and validation without Android view or WebView dependencies. */
class AddDownloadViewModel(
    private val prober: DownloadProber,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    data class UiState(
        val link: String = "", val referrer: String = "", val name: String = "", val extension: String = "",
        val size: Long? = null, val resume: Boolean? = null, val finalUrl: String? = null,
        val busy: Boolean = false, val html: Boolean = false, val error: String? = null,
        /** Host whose certificate failed validation; the dialog offers to ignore it (1DM "ignore SSL errors"). */
        val insecureHost: String? = null,
        /** Metadata was fetched with certificate validation disabled for this host. */
        val insecure: Boolean = false,
    ) {
        /** Connect succeeded for the current link; the dialog may offer START. */
        val probed: Boolean get() = size != null && !html && error == null && !busy
    }
    private val mutableState = MutableStateFlow(UiState())
    val state = mutableState.asStateFlow()
    private var revision = 0

    fun link(value: String) {
        revision++
        mutableState.value = UiState(link = value, referrer = state.value.referrer)
    }
    fun referrer(value: String) {
        revision++
        mutableState.value = state.value.copy(referrer = value, busy = false)
    }
    fun name(value: String) { mutableState.value = state.value.copy(name = value) }
    fun extension(value: String) { mutableState.value = state.value.copy(extension = value.trimStart('.')) }

    suspend fun probe(userAgent: String) {
        val version = revision
        val input = state.value
        mutableState.value = input.copy(busy = true, error = null, insecureHost = null)
        try {
            val result = withContext(io) {
                val url = FilenameResolver.normalizeUrl(input.link)
                // Interactive probe: a single attempt so the real failure reason shows up right away.
                prober.probe(DownloadRequest(url, FilenameResolver.resolve(url), targetDir = File("."),
                    referer = input.referrer.trim().takeIf { it.isNotEmpty() }, userAgent = userAgent, retryOnFailure = false))
            }
            if (revision != version) return
            val filename = result.fileName ?: FilenameResolver.resolve(result.finalUrl)
            val dot = filename.lastIndexOf('.').takeIf { it > 0 } ?: filename.length
            mutableState.value = state.value.copy(name = filename.substring(0, dot),
                extension = if (dot < filename.length) filename.substring(dot + 1) else "",
                size = result.totalBytes, resume = result.acceptsRanges, finalUrl = result.finalUrl, busy = false, html = false,
                insecure = TlsPolicy.isInsecure(hostOf(input.link)))
        } catch (cancelled: CancellationException) {
            if (revision == version) mutableState.value = state.value.copy(busy = false)
            throw cancelled
        } catch (page: NeedsBrowser) {
            if (revision == version) mutableState.value = state.value.copy(busy = false, html = true,
                finalUrl = page.url, error = "This is a web page, not a file")
        } catch (failure: Exception) {
            if (revision == version) {
                val host = hostOf(input.link)
                val certificate = host != null && TlsPolicy.isCertificateFailure(failure)
                mutableState.value = state.value.copy(busy = false, insecureHost = host.takeIf { certificate },
                    error = if (certificate) "Certificate error: the server's certificate is not valid for $host. " +
                        "Tap IGNORE CERTIFICATE to download anyway (unsafe).\n${failure.message.orEmpty()}"
                    else failure.message?.takeIf { it.isNotBlank() } ?: "${failure.javaClass.simpleName}: probe failed")
            }
        }
    }

    /** 1DM "ignore SSL errors": stop validating the host that just failed and connect again. */
    suspend fun probeIgnoringCertificate(userAgent: String) {
        val host = state.value.insecureHost ?: return
        TlsPolicy.allowInsecure(host)
        probe(userAgent)
    }

    /** Re-enables certificate validation for the current host and discards metadata fetched without it. */
    fun restoreCertificateCheck() {
        hostOf(state.value.link)?.let(TlsPolicy::requireSecure)
        link(state.value.link)
    }

    private fun hostOf(link: String): String? =
        runCatching { URI(FilenameResolver.normalizeUrl(link)).host }.getOrNull()?.takeIf { it.isNotBlank() }
}
