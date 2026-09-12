package app.onedown.feature.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
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
import app.onedown.core.engine.DownloadState
import java.util.UUID
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
)

/** One independently navigable WebView and its observed media candidates. */
class BrowserTab(val id: String, val webView: WebView) {
    var url by mutableStateOf(BrowserPolicy.HOME)
    var title by mutableStateOf("New tab")
    var progress by mutableStateOf(0)
    var back by mutableStateOf(false)
    var forward by mutableStateOf(false)
    val media = mutableStateListOf<BrowserCapture>()
}

/** Activity-owned WebViews; never stores an Activity inside a ViewModel. */
class BrowserSession(private val context: Context) {
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
    private var closed = false
    var visible = true
    val active: BrowserTab? get() = tabs.find { it.id == selected }

    fun newTab(url: String = BrowserPolicy.HOME): BrowserTab {
        val tab = createTab()
        tabs.add(tab)
        selected = tab.id
        navigate(url)
        return tab
    }

    fun closeTab(id: String) {
        val tab = tabs.find { it.id == id } ?: return
        if (capture?.tabId == id) capture = null
        writer.cancel()
        tabs.remove(tab)
        tab.webView.stopLoading()
        tab.webView.removeJavascriptInterface("OneDownBlob")
        (tab.webView.parent as? android.view.ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        if (tabs.isEmpty()) newTab() else if (selected == id) selected = tabs.last().id
    }

    fun navigate(input: String) {
        val tab = active ?: return
        val url = BrowserPolicy.address(input)
        if (!route(tab, url)) tab.webView.loadUrl(url)
    }

    fun reopen(state: DownloadState) {
        refresh = state
        val tab = newTab()
        refreshTabId = tab.id
        tab.webView.loadUrl(requireNotNull(state.request.referrerPageUrl))
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
        closed = true
        CookieManager.getInstance().flush()
        writer.close()
        popups.toList().forEach { it.destroy() }
        popups.clear()
        tabs.forEach {
            (it.webView.parent as? android.view.ViewGroup)?.removeView(it.webView)
            it.webView.removeJavascriptInterface("OneDownBlob")
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
        val view = WebView(context)
        val tab = BrowserTab(UUID.randomUUID().toString(), view)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
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
        view.addJavascriptInterface(writer.bridge(view), "OneDownBlob")
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
                writer.navigation(view)
                tab.media.clear()
                if (capture?.tabId == tab.id && capture?.url?.startsWith("blob:") == true) capture = null
                update(tab)
                tab.url = url ?: tab.url
            }

            override fun onPageFinished(view: WebView, url: String?) { update(tab) }

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
            override fun onProgressChanged(view: WebView, newProgress: Int) { tab.progress = newProgress; update(tab) }
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
                    if (!route(tab, url)) view.loadUrl(url)
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

    private fun route(tab: BrowserTab, url: String): Boolean {
        if (url.startsWith("magnet:", true)) { toast("Torrent not supported yet"); return true }
        if (url.startsWith("blob:") || url.startsWith("data:")) { captureLink(tab.id, url); return true }
        if (url.toHttpUrlOrNull() == null) { toast("Unsupported link scheme"); return true }
        if (!(refresh != null && refreshTabId == tab.id) && BrowserPolicy.downloadable(url, extensions)) {
            captureLink(tab.id, url); return true
        }
        return false
    }

    private fun buildCapture(tab: BrowserTab, url: String, ua: String?, disposition: String?, mime: String?, length: Long,
        sentHeaders: Map<String, String> = emptyMap()): BrowserCapture {
        val headers = BrowserPolicy.replayHeaders(sentHeaders).toMutableMap()
        fun set(name: String, value: String?) {
            if (value == null) return
            headers.keys.filter { it.equals(name, true) }.toList().forEach { headers.remove(it) }
            headers[name] = value
        }
        set("User-Agent", ua ?: tab.webView.settings.userAgentString)
        set("Cookie", CookieManager.getInstance().getCookie(url))
        set("Referer", tab.webView.url?.takeIf { it.toHttpUrlOrNull() != null })
        set("Accept", headers.entries.find { it.key.equals("Accept", true) }?.value ?: "*/*")
        val name = BrowserPolicy.sanitize(app.onedown.core.engine.RangeProbe.parseContentDisposition(disposition)
            ?: URLUtil.guessFileName(if (url.startsWith("data:")) "download" else url, disposition, mime))
        return BrowserCapture(url, name, length, mime, headers, tab.webView.url?.takeIf { it.toHttpUrlOrNull() != null }, tab.id)
    }

    private fun captureDownload(tab: BrowserTab, url: String, ua: String?, disposition: String?, mime: String?, length: Long,
        listener: Boolean = false) {
        if (closed) return
        if (url.toHttpUrlOrNull() == null && !url.startsWith("blob:") && !url.startsWith("data:")) {
            toast("Unsupported download URL"); return
        }
        var next = buildCapture(tab, url, ua, disposition, mime, length)
        val target = refresh
        if (listener && target != null && refreshTabId == tab.id && url.toHttpUrlOrNull() != null && BrowserPolicy.matches(target, next.fileName, length)) {
            next = next.copy(refreshId = target.id, fileName = target.fileName)
        }
        if (capture == null) capture = next else toast("Finish or dismiss the current download confirmation first")
    }

    private fun toast(message: String) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
}