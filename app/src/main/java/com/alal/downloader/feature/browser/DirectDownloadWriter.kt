package com.alal.downloader.feature.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.alal.downloader.core.data.AndroidDownloadStorage
import com.alal.downloader.core.engine.DownloadOutput
import com.alal.downloader.core.engine.DownloadRequest
import com.alal.downloader.core.engine.DownloadState
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

/** Page-bound blob/data writer; only an explicitly approved destination is writable. */
class DirectDownloadWriter(context: Context, private val report: (String) -> Unit) {
    private val storage = AndroidDownloadStorage(context.applicationContext)
    private val resolver = context.applicationContext.contentResolver
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var token: String? = null
    private var output: DownloadOutput? = null
    private var state: DownloadState? = null
    private var offset = 0L
    private var sequence = 0
    private var expected = -1L
    private var outstanding = false
    private var page: WebView? = null
    private var pageToken: String? = null
    private var lastProgress = 0L
    private var closed = false

    fun bridge(webView: WebView): PageBridge = PageBridge(webView)

    /** Capability checks bind callbacks to their owning WebView as well as a session token. */
    inner class PageBridge(private val webView: WebView) {
        @JavascriptInterface
        fun chunk(key: String, index: Int, total: String, encoded: String, last: Boolean): Boolean =
            synchronized(this@DirectDownloadWriter) {
                if (page !== webView) false else this@DirectDownloadWriter.chunk(key, index, total, encoded, last)
            }

        @JavascriptInterface
        fun failed(key: String) {
            synchronized(this@DirectDownloadWriter) { if (page === webView) this@DirectDownloadWriter.failed(key) }
        }
    }

    @Synchronized
    fun start(webView: WebView, request: DownloadRequest) {
        check(!closed) { "Browser closed" }
        check(token == null) { "A page download is already active" }
        val key = UUID.randomUUID().toString()
        token = key
        page = webView
        pageToken = key
        executor.execute {
            try {
                check(token == key) { "Download cancelled" }
                state = storage.prepare(DownloadState(UUID.randomUUID().toString(), request))
                output = storage.open(requireNotNull(state)).also { it.resize(0) }
                offset = 0
                sequence = 0
                expected = -1
                if (request.url.startsWith("data:", true)) {
                    BrowserPolicy.decodeData(request.url, object : OutputStream() {
                        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                        override fun write(bytes: ByteArray, start: Int, count: Int) {
                            check(token == key) { "Download cancelled" }
                            val block = if (start == 0) bytes else bytes.copyOfRange(start, start + count)
                            requireNotNull(output).write(offset, block, count)
                            offset += count
                        }
                    }) { update("Saving $it bytes") }
                    finish(key)
                } else {
                    val script = blobScript(key, request.url)
                    main.post { if (token == key) {
                        webView.evaluateJavascript(script, null)
                    } }
                    main.postDelayed({ if (token == key) cancel("Blob download timed out; retry from the page") }, 30 * 60 * 1000L)
                }
            } catch (failure: Exception) { fail(key, failure) }
        }
    }

    @Synchronized
    private fun chunk(key: String, index: Int, total: String, encoded: String, last: Boolean): Boolean {
        if (key != token || outstanding || encoded.length > 65536 || index != sequence) return false
        val size = total.toLongOrNull() ?: return false
        if (size < 0 || size > 1L.shl(50)) return false
        outstanding = true
        executor.execute {
            try {
                check(token == key) { "Download cancelled" }
                if (expected < 0) expected = size
                check(expected == size)
                val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                check(bytes.size <= 48 * 1024 && (bytes.isNotEmpty() || last))
                check(offset <= expected - bytes.size) { "Blob exceeded declared size" }
                requireNotNull(output).write(offset, bytes, bytes.size)
                offset += bytes.size
                sequence++
                update("Saving $offset / $expected bytes")
                if (last) {
                    check(offset == expected) { "Incomplete blob" }
                    finish(key)
                } else {
                    synchronized(this) { outstanding = false }
                    main.post {
                        if (token == key) page?.evaluateJavascript("window[${JSONObject.quote("alal_$key")}]?.();", null)
                    }
                }
            } catch (failure: Exception) { fail(key, failure) }
        }
        return true
    }

    private fun failed(key: String) {
        synchronized(this) {
            if (key != token || outstanding) return
            outstanding = true
            executor.execute { fail(key, IllegalStateException("Blob unavailable in this page/frame; retry its download button")) }
        }
    }

    @Synchronized
    fun cancel(message: String = "Page download cancelled") {
        if (closed) return
        val active = token != null
        token = null
        outstanding = false
        removePageBridge()
        executor.execute { discard() }
        if (active) update(message)
    }

    @Synchronized
    fun close() {
        if (closed) return
        cancel()
        closed = true
        main.removeCallbacksAndMessages(null)
        executor.shutdown()
    }

    fun navigation(webView: WebView) {
        if (page === webView) cancel("Page changed; save cancelled")
    }

    private fun finish(key: String) {
        check(token == key) { "Download cancelled" }
        requireNotNull(output).sync()
        closeOutput()
        storage.complete(requireNotNull(state))
        val name = state?.fileName
        state = null
        synchronized(this) { if (token == key) { token = null; outstanding = false } }
        main.post { if (pageToken == key) removePageBridge() }
        update("Saved $name ($offset bytes)")
    }

    private fun fail(key: String, failure: Exception) {
        discard()
        synchronized(this) { if (token == key) { token = null; outstanding = false } }
        main.post { if (pageToken == key) removePageBridge() }
        update("Save failed: ${failure.message}")
    }

    private fun closeOutput() {
        try { output?.close() } catch (_: Exception) { } finally { output = null }
    }

    private fun discard() {
        closeOutput()
        val partial = state
        state = null
        try {
            partial?.destinationUri?.let { value ->
                val uri = android.net.Uri.parse(value)
                if (partial.request.destinationKind == "tree") android.provider.DocumentsContract.deleteDocument(resolver, uri)
                else resolver.delete(uri, null, null)
            }
        } catch (_: Exception) { update("Could not remove partial page download; remove it from the selected folder") }
    }

    private fun removePageBridge() {
        pageToken?.let { key -> page?.evaluateJavascript("delete window[${JSONObject.quote("alal_$key")}];", null) }
        page = null
        pageToken = null
    }

    private fun update(message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (message.startsWith("Saving") && now - lastProgress < 200) return
        lastProgress = now
        main.post { report(message) }
    }

    private fun blobScript(key: String, url: String): String = """
        (async () => {
          const key = ${JSONObject.quote(key)}, name = ${JSONObject.quote("alal_$key")};
          try {
            const response = await fetch(${JSONObject.quote(url)});
            if (!response.ok) throw new Error('Blob fetch failed');
            const blob = await response.blob();
            let offset = 0, index = 0;
            window[name] = async () => {
              try {
                const end = Math.min(offset + 49152, blob.size);
                const bytes = new Uint8Array(await blob.slice(offset, end).arrayBuffer());
                let binary = '';
                for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
                const last = end === blob.size;
                if (!AlalBlob.chunk(key, index++, String(blob.size), btoa(binary), last)) throw new Error('Rejected chunk');
                offset = end;
                if (last) delete window[name];
              } catch (_) { delete window[name]; AlalBlob.failed(key); }
            };
            window[name]();
          } catch (_) { delete window[name]; AlalBlob.failed(key); }
        })();
    """.trimIndent()
}