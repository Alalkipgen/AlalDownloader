package com.alal.downloader.feature.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alal.downloader.core.engine.DownloadRequest
import com.alal.downloader.core.engine.DownloadState
import com.alal.downloader.core.engine.ProbeResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Immutable capture including the browser's request context and owning page. */
data class BrowserCapture(
    val url: String,
    val fileName: String,
    val size: Long,
    val mimeType: String?,
    val headers: Map<String, String>,
    val referrer: String?,
    val tabId: String,
    val refreshId: String? = null,
    val document: Boolean = false,
)

/** One independently navigable WebView and its observed media candidates. */
class BrowserTab(val id: String, val webView: WebView) {
    var url by mutableStateOf(BrowserPolicy.HOME)
    var title by mutableStateOf("New tab")
    var progress by mutableStateOf(0)
    var back by mutableStateOf(false)
    var forward by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var finishedLoads by mutableStateOf(0)
    val media = mutableStateListOf<BrowserCapture>()
    internal var navigation = 0
    internal var inspected = -1
    internal val offered = LinkedHashSet<String>()
}

/** Activity-owned WebViews; never stores an Activity inside a ViewModel. */
class BrowserSession(private val context: Context, private val probe: suspend (DownloadRequest) -> ProbeResult) {
    val settings = BrowserSettings(context.applicationContext)
    val tabs = mutableStateListOf<BrowserTab>()
    var selected by mutableStateOf("")
    var capture by mutableStateOf<BrowserCapture?>(null)
    var linkMenu by mutableStateOf<Pair<String, String>?>(null)
    var refresh by mutableStateOf<DownloadState?>(null)
    private var refreshTabId: String? = null
    var directProgress by mutableStateOf<String?>(null)
    var blockPopups by mutableStateOf(settings.blockPopups)
    var desktop by mutableStateOf(settings.desktop)
    var clipboardEnabled by mutableStateOf(settings.clipboard)
    var mediaEnabled by mutableStateOf(settings.media)
    var extensions by mutableStateOf(settings.extensions)
    val writer = DirectDownloadWriter(context) { directProgress = it }
    private val popups = mutableSetOf<WebView>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var closed = false
    var visible = true
    val active: BrowserTab? get() = tabs.find { it.id == selected }

    init {
        Log.d("Browser", "session init tabs=${tabs.size}")
        ensureActiveTab()
    }

    fun ensureActiveTab(): BrowserTab {
        check(!closed) { "Browser session has been disposed" }
        return active ?: tabs.firstOrNull()?.also { selected = it.id } ?: newTab()
    }

    fun newTab(url: String = BrowserPolicy.HOME): BrowserTab {
        check(!closed) { "Browser session has been disposed" }
        val tab = createTab()
        tabs.add(tab)
        selected = tab.id
        Log.d("Browser", "newTab tabs=${tabs.size} active=${tab.id}")
        navigate(url)
        return tab
    }

    fun closeTab(id: String) {
        val tab = tabs.find { it.id == id } ?: return
        if (capture?.tabId == id) capture = null
        writer.cancel()
        tabs.remove(tab)
        tab.webView.stopLoading()
        tab.webView.removeJavascriptInterface("AlalBlob")
        (tab.webView.parent as? android.view.ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        if (tabs.isEmpty()) newTab() else if (selected == id) selected = tabs.last().id
    }

    fun navigate(input: String) {
        val tab = ensureActiveTab()
        val url = BrowserPolicy.address(input)
        Log.d("Browser", "navigate active=${tab.id}")
        if (!route(tab, url)) load(tab, url)
    }

    private fun load(tab: BrowserTab, url: String) {
        tab.error = null
        Log.d("Browser", "loadUrl on ${System.identityHashCode(tab.webView)} host=${url.toHttpUrlOrNull()?.host}")
        tab.webView.loadUrl(url)
    }

    fun retry(tab: BrowserTab) {
        require(tab in tabs)
        load(tab, tab.url)
    }

    fun reopen(state: DownloadState) {
        refresh = state
        val tab = newTab()
        refreshTabId = tab.id
        load(tab, requireNotNull(state.request.referrerPageUrl))
    }

    fun copyLink(url: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Link", url))
        toast("Link copied")
    }

    fun captureLink(tabId: String, url: String) {
        val tab = tabs.find { it.id == tabId } ?: return
        captureDownload(tab, url, tab.webView.settings.userAgentString, null, null, -1)
    }

    fun setDesktopMode(value: Boolean) {
        desktop = value
        settings.desktop = value
        tabs.forEach { it.webView.settings.userAgentString = userAgent(value) }
        active?.webView?.reload()
    }

    fun pause() {
        tabs.forEach { it.webView.onPause() }
        CookieManager.getInstance().flush()
    }

    fun resume() { if (visible) active?.webView?.onResume() }

    fun close() {
        Log.d("Browser", "session close tabs=${tabs.size}")
        closed = true
        scope.cancel()
        CookieManager.getInstance().flush()
        writer.close()
        popups.toList().forEach { it.destroy() }
        popups.clear()
        tabs.forEach {
            (it.webView.parent as? android.view.ViewGroup)?.removeView(it.webView)
            it.webView.removeJavascriptInterface("AlalBlob")
            it.webView.stopLoading()
            it.webView.destroy()
        }
        tabs.clear()
    }

    private fun userAgent(desktop: Boolean): String {
        val normal = WebSettings.getDefaultUserAgent(context)
        return if (desktop) normal.replace(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
            .replace("; wv", "").replace(" Version/4.0", "").replace(" Mobile", "") else normal
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createTab(): BrowserTab {
        val view = WebView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val tab = BrowserTab(UUID.randomUUID().toString(), view)
        Log.d("Browser", "createTab webView=${System.identityHashCode(view)}")
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = !blockPopups
            userAgentString = userAgent(desktop)
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(view, true) }
        view.addJavascriptInterface(writer.bridge(view), "AlalBlob")
        view.setDownloadListener { url, ua, disposition, mime, length ->
            captureDownload(tab, url, ua, disposition, mime, length, listener = true)
        }
        view.setOnLongClickListener {
            val hit = view.hitTestResult
            if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hit.extra?.let { linkMenu = tab.id to it }
                hit.extra != null
            } else false
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                if (request.isForMainFrame) route(tab, request.url.toString())
                else request.url.scheme !in setOf("http", "https", "about")

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                Log.d("Browser", "onPageStarted webView=${System.identityHashCode(view)} host=${url?.toHttpUrlOrNull()?.host}")
                tab.navigation++
                tab.error = null
                writer.navigation(view)
                tab.media.clear()
                if (capture?.tabId == tab.id && capture?.url?.startsWith("blob:") == true) capture = null
                update(tab)
                tab.url = url ?: tab.url
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (tab.error == null) context.historyRepository().record(url, view.title, view.url?.toHttpUrlOrNull()?.host, view.favicon)
                update(tab)
                tab.url = url ?: tab.url
                tab.finishedLoads++
                Log.d("Browser", "onPageFinished webView=${System.identityHashCode(view)}")
                inspectDocument(tab)
            }

            override fun onPageCommitVisible(view: WebView, url: String?) { inspectDocument(tab) }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    tab.error = "${error.errorCode}: ${error.description}"
                    Log.e("Browser", "onReceivedError webView=${System.identityHashCode(view)} code=${error.errorCode}")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) {
                    tab.error = "HTTP ${response.statusCode}: ${response.reasonPhrase}"
                    Log.e("Browser", "onReceivedHttpError status=${response.statusCode}")
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                val headers = request.requestHeaders.toMap()
                if (request.method == "GET" && BrowserPolicy.mediaCandidate(url)) view.post {
                    if (!closed && mediaEnabled && tab in tabs && tab.media.size < 100 && tab.media.none { it.url == url }) {
                        tab.media.add(buildCapture(tab, url, view.settings.userAgentString, null, null, -1, headers))
                    }
                }
                return null
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                Log.d("Browser", "onProgressChanged webView=${System.identityHashCode(view)} progress=$newProgress")
                tab.progress = newProgress
                update(tab)
            }
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("BrowserConsole", "level=${message.messageLevel()} line=${message.lineNumber()}")
                return true
            }
            override fun onReceivedTitle(view: WebView, title: String?) { tab.title = title ?: "Tab" }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (blockPopups && (!isUserGesture || isDialog)) { toast("Popup blocked"); return false }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(context)
                popups.add(popup)
                var routed = false
                fun forward(url: String): Boolean {
                    if (routed || url == "about:blank") return false
                    routed = true
                    if (!route(tab, url)) load(tab, url)
                    popup.post { popups.remove(popup); popup.stopLoading(); popup.destroy() }
                    return true
                }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = forward(request.url.toString())
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { url?.let { forward(it) } }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                view.postDelayed({ if (popups.remove(popup)) { popup.stopLoading(); popup.destroy() } }, 15_000)
                return true
            }
        }
        return tab
    }

    private fun update(tab: BrowserTab) {
        tab.webView.url?.let { tab.url = it }
        tab.back = tab.webView.canGoBack()
        tab.forward = tab.webView.canGoForward()
    }

    private fun route(tab: BrowserTab, url: String): Boolean = when (BrowserPolicy.navigation(url)) {
        BrowserPolicy.Navigation.LOAD -> false
        BrowserPolicy.Navigation.CAPTURE -> { captureLink(tab.id, url); true }
        BrowserPolicy.Navigation.MAGNET -> { toast("Torrent not supported yet"); true }
        BrowserPolicy.Navigation.UNSUPPORTED -> { toast("Unsupported link scheme"); true }
    }

    private fun buildCapture(tab: BrowserTab, url: String, ua: String?, disposition: String?, mime: String?, length: Long,
        sentHeaders: Map<String, String> = emptyMap(),
        referrer: String? = tab.webView.url?.takeIf { it.toHttpUrlOrNull() != null }): BrowserCapture {
        val headers = BrowserPolicy.replayHeaders(sentHeaders).toMutableMap()
        fun set(name: String, value: String?) {
            if (value == null) return
            headers.keys.filter { it.equals(name, true) }.toList().forEach { headers.remove(it) }
            headers[name] = value
        }
        set("User-Agent", tab.webView.settings.userAgentString)
        set("Cookie", CookieManager.getInstance().getCookie(url))
        set("Referer", referrer)
        set("Accept", headers.entries.find { it.key.equals("Accept", true) }?.value ?: "*/*")
        val name = BrowserPolicy.sanitize(com.alal.downloader.core.engine.RangeProbe.parseContentDisposition(disposition)
            ?: URLUtil.guessFileName(if (url.startsWith("data:")) "download" else url, disposition, mime))
        return BrowserCapture(url, name, length, mime, headers, referrer, tab.id)
    }

    private fun captureDownload(tab: BrowserTab, url: String, ua: String?, disposition: String?, mime: String?, length: Long,
        listener: Boolean = false) {
        if (closed) return
        if (url.toHttpUrlOrNull() == null && !url.startsWith("blob:") && !url.startsWith("data:")) {
            toast("Unsupported download URL"); return
        }
        offer(buildCapture(tab, url, ua, disposition, mime, length), listener)
    }

    private fun offer(candidate: BrowserCapture, listener: Boolean): Boolean {
        var next = candidate
        val target = refresh
        if (listener && target != null && refreshTabId == next.tabId && next.url.toHttpUrlOrNull() != null &&
            BrowserPolicy.matches(target, next.fileName, next.size)) {
            next = next.copy(refreshId = target.id, fileName = target.fileName)
        }
        if (capture != null) { toast("Finish or dismiss the current download confirmation first"); return false }
        capture = next
        if (next.document) tabs.find { it.id == next.tabId }?.webView?.evaluateJavascript(PAUSE_MEDIA, null)
        return true
    }

    private fun inspectDocument(tab: BrowserTab) {
        val view = tab.webView
        val url = view.url
        val navigation = tab.navigation
        if (closed || tab.inspected == navigation || tab.error != null || url?.toHttpUrlOrNull() == null) return
        tab.inspected = navigation
        view.evaluateJavascript("document.contentType") { result ->
            if (closed || tab !in tabs || tab.navigation != navigation || tab.error != null || view.url != url) return@evaluateJavascript
            val type = BrowserPolicy.documentType(result)
            if (type != null && BrowserPolicy.offersDocument(type, url, extensions)) offerDocument(tab, url, type)
        }
    }

    private fun offerDocument(tab: BrowserTab, url: String, type: String) {
        if (capture?.url == url || !tab.offered.add(url)) return
        while (tab.offered.size > 32) tab.offered.remove(tab.offered.first())
        val history = tab.webView.copyBackForwardList()
        val referrer = BrowserPolicy.documentReferrer(List(history.size) { history.getItemAtIndex(it)?.url }, history.currentIndex, url)
        val base = buildCapture(tab, url, null, null, type, -1, referrer = referrer).copy(document = true)
        scope.launch {
            val result = try {
                probe(DownloadRequest(url, base.fileName, base.headers, referrer, File("."), mimeType = type, retryOnFailure = false))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (closed || tab !in tabs || tab.webView.url != url) { tab.offered.remove(url); return@launch }
            val (name, size) = BrowserPolicy.documentDetails(result, base.fileName)
            if (!offer(base.copy(fileName = name, size = size), listener = true)) tab.offered.remove(url)
        }
    }

    fun submitted(done: BrowserCapture) {
        capture = null
        val view = tabs.find { it.id == done.tabId }?.webView ?: return
        if (done.document && view.url == done.url) {
            view.evaluateJavascript(STOP_MEDIA, null)
            view.stopLoading()
        }
    }

    private fun toast(message: String) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }

    private companion object {
        const val PAUSE_MEDIA = "document.querySelectorAll('video,audio').forEach(function(m){m.pause()})"
        const val STOP_MEDIA = "document.querySelectorAll('video,audio').forEach(function(m){m.pause();m.removeAttribute('src');" +
            "m.querySelectorAll('source').forEach(function(s){s.remove()});m.load()})"
    }
}