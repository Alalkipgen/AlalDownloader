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
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
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
    internal var userChain = false
    internal var openerId: String? = null
    internal var committed = false
    @Volatile internal var tapKey = ""
    @Volatile internal var tap: RecordedTap? = null
    internal var pending: String? = null
}

/** The single guard bar: a blocked popup (Open in a new tab) or a blocked redirect (Open here, Allow on its site). */
data class GuardNotice(val tabId: String, val url: String, val host: String, val site: String?, val referer: String?,
    val popup: Boolean)

/** Activity-owned WebViews; never stores an Activity inside a ViewModel. */
class BrowserSession(private val context: Context, private val probe: suspend (DownloadRequest) -> ProbeResult) {
    val settings = BrowserSettings(context.applicationContext)
    val tabs = mutableStateListOf<BrowserTab>()
    var selected by mutableStateOf("")
        private set
    var capture by mutableStateOf<BrowserCapture?>(null)
    var linkMenu by mutableStateOf<Pair<String, String>?>(null)
    var refresh by mutableStateOf<DownloadState?>(null)
    private var refreshTabId: String? = null
    var directProgress by mutableStateOf<String?>(null)
    var blockPopups by mutableStateOf(settings.blockPopups)
        private set
    var blockRedirects by mutableStateOf(settings.blockRedirects)
        private set
    var redirectSites by mutableStateOf(settings.redirectSites)
        private set
    var notice by mutableStateOf<GuardNotice?>(null)
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
    private val store = BrowserStore(context.applicationContext)
    var bookmarks by mutableStateOf(store.bookmarks())
        private set

    /** Whether the current page is bookmarked; null when it cannot be (not an http(s) page). */
    val starred: Boolean? get() = active?.let { BrowserState.page(it.url, it.title) }?.let { BrowserState.bookmarked(bookmarks, it.url) }

    init {
        Log.d("Browser", "session init tabs=${tabs.size}")
        restore()
        ensureActiveTab()
        scope.launch(Dispatchers.Default) { BrowserPolicy.site(BrowserPolicy.HOME) }
        scope.launch { snapshotFlow { savedTabs() }.conflate().collect { store.saveTabs(it); delay(SAVE_INTERVAL_MS) } }
    }

    fun ensureActiveTab(): BrowserTab {
        check(!closed) { "Browser session has been disposed" }
        return active ?: tabs.firstOrNull()?.also { select(it.id) } ?: newTab()
    }

    /** Shows a tab; a restored tab loads its address (user-started) the first time it is shown. */
    fun select(id: String) {
        val tab = tabs.find { it.id == id } ?: return
        selected = tab.id
        if (visible) loadPending(tab)
    }

    private fun loadPending(tab: BrowserTab) { tab.pending?.let { load(tab, it) } }

    /** The tabs of the last run come back with their address and title, in order, with the same one selected; none loads before it is shown. */
    private fun restore() {
        val saved = store.tabs() ?: return
        saved.pages.forEach { page ->
            val tab = createTab()
            tab.url = page.url
            tab.title = page.title
            tab.pending = page.url
            tabs.add(tab)
        }
        selected = tabs[saved.selected].id
        Log.d("Browser", "restored tabs=${tabs.size}")
    }

    private fun savedTabs(): SavedTabs? {
        val pages = tabs.mapNotNull { tab -> BrowserState.page(tab.url, tab.title)?.let { tab.id to it } }.take(BrowserState.MAX_TABS)
        if (pages.isEmpty()) return null
        return SavedTabs(pages.map { it.second }, pages.indexOfFirst { it.first == selected }.coerceAtLeast(0))
    }

    /** The star: adds the current page (title, address) or removes it. */
    fun toggleBookmark() {
        val page = active?.let { BrowserState.page(it.url, it.title) } ?: return
        bookmarks = if (BrowserState.bookmarked(bookmarks, page.url)) BrowserState.removeBookmark(bookmarks, page.url)
            else BrowserState.addBookmark(bookmarks, page)
        store.saveBookmarks(bookmarks)
    }

    fun removeBookmark(url: String) {
        bookmarks = BrowserState.removeBookmark(bookmarks, url)
        store.saveBookmarks(bookmarks)
    }

    fun newTab(url: String = BrowserPolicy.HOME, referer: String? = null, opener: BrowserTab? = null): BrowserTab {
        check(!closed) { "Browser session has been disposed" }
        val tab = createTab()
        tab.openerId = opener?.id
        tabs.add(tab)
        select(tab.id)
        Log.d("Browser", "newTab tabs=${tabs.size} active=${tab.id}")
        val address = if (opener != null) url else BrowserPolicy.address(url)
        if (!route(tab, address)) load(tab, address, referer)
        return tab
    }

    fun closeTab(id: String) {
        val tab = tabs.find { it.id == id } ?: return
        if (capture?.tabId == id) capture = null
        writer.cancel()
        discard(tab)
    }

    private fun discard(tab: BrowserTab) {
        if (tab !in tabs) return
        if (capture?.tabId == tab.id) capture = null
        if (notice?.tabId == tab.id) notice = null
        tabs.remove(tab)
        tab.webView.stopLoading()
        tab.webView.removeJavascriptInterface("AlalBlob")
        tab.webView.removeJavascriptInterface(TapRecorder.NAME)
        (tab.webView.parent as? android.view.ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        if (tabs.isEmpty()) newTab() else if (selected == tab.id) select(tabs.last().id)
    }

    fun navigate(input: String) {
        val tab = ensureActiveTab()
        val url = BrowserPolicy.address(input)
        Log.d("Browser", "navigate active=${tab.id}")
        if (!route(tab, url)) load(tab, url)
    }

    /** App-started loads (URL bar, tabs, Reopen page, a bar's Open) are user-started: their server redirects follow. */
    private fun load(tab: BrowserTab, url: String, referer: String? = null) {
        tab.error = null
        tab.pending = null
        tab.userChain = true
        Log.d("Browser", "loadUrl on ${System.identityHashCode(tab.webView)} host=${url.toHttpUrlOrNull()?.host}")
        if (referer == null) tab.webView.loadUrl(url) else tab.webView.loadUrl(url, mapOf("Referer" to referer))
    }

    fun back() { active?.takeIf { it.webView.canGoBack() }?.let { it.userChain = true; it.webView.goBack() } }

    fun forward() { active?.takeIf { it.webView.canGoForward() }?.let { it.userChain = true; it.webView.goForward() } }

    fun reload() { active?.let { it.error = null; it.userChain = true; it.webView.reload() } }

    fun openBlocked() {
        val blocked = notice ?: return
        notice = null
        val tab = tabs.find { it.id == blocked.tabId }
        if (blocked.popup) { newTab(blocked.url, blocked.referer, tab); return }
        if (tab == null) return
        select(tab.id)
        load(tab, blocked.url, blocked.referer)
    }

    fun allowBlocked() {
        val blocked = notice ?: return
        notice = null
        blocked.site?.let(::allowSite)
    }

    fun allowSite(site: String) { redirectSites = redirectSites + site; settings.redirectSites = redirectSites }

    fun removeSite(site: String) { redirectSites = redirectSites - site; settings.redirectSites = redirectSites }

    fun setPopupBlocking(value: Boolean) { blockPopups = value; settings.blockPopups = value }

    fun setRedirectBlocking(value: Boolean) { blockRedirects = value; settings.blockRedirects = value }

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
        reload()
    }

    fun pause() {
        if (!closed) store.saveTabs(savedTabs())
        tabs.forEach { it.webView.onPause() }
        CookieManager.getInstance().flush()
    }

    fun resume() { if (visible) active?.let { loadPending(it); it.webView.onResume() } }

    fun close() {
        Log.d("Browser", "session close tabs=${tabs.size}")
        if (!closed) store.saveTabs(savedTabs())
        closed = true
        scope.cancel()
        CookieManager.getInstance().flush()
        writer.close()
        popups.toList().forEach { it.destroy() }
        popups.clear()
        tabs.forEach {
            (it.webView.parent as? android.view.ViewGroup)?.removeView(it.webView)
            it.webView.removeJavascriptInterface("AlalBlob")
            it.webView.removeJavascriptInterface(TapRecorder.NAME)
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
            javaScriptCanOpenWindowsAutomatically = true
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
        view.addJavascriptInterface(TapRecorder(tab), TapRecorder.NAME)
        view.setDownloadListener { url, ua, disposition, mime, length ->
            tab.userChain = false
            val opener = tab.openerId?.takeIf { !tab.committed }?.let { id -> tabs.find { it.id == id } }
            captureDownload(opener ?: tab, url, ua, disposition, mime, length, listener = true)
            if (opener != null) { select(opener.id); scope.launch(Dispatchers.Main) { discard(tab) } }
        }
        view.setOnLongClickListener {
            val hit = view.hitTestResult
            if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hit.extra?.let { linkMenu = tab.id to it }
                hit.extra != null
            } else false
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return request.url.scheme !in setOf("http", "https", "about")
                val url = request.url.toString()
                return route(tab, url) || !guard(tab, view.url, url, request.isRedirect, request.hasGesture())
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                Log.d("Browser", "onPageStarted webView=${System.identityHashCode(view)} host=${url?.toHttpUrlOrNull()?.host}")
                tab.navigation++
                tab.error = null
                tab.committed = true
                tab.userChain = false
                tab.tap = null
                tab.tapKey = UUID.randomUUID().toString()
                if (notice?.tabId == tab.id) notice = null
                recordTaps(tab)
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
                recordTaps(tab)
                inspectDocument(tab)
            }

            override fun onPageCommitVisible(view: WebView, url: String?) { recordTaps(tab); inspectDocument(tab) }

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
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                if (closed) return false
                if (popups.size >= MAX_POPUPS) popups.first().let { popups.remove(it); it.stopLoading(); it.destroy() }
                val opener = view.url
                val popup = WebView(context)
                popups.add(popup)
                var decided = false
                fun dismiss() { if (popups.remove(popup)) { popup.stopLoading(); popup.destroy() } }
                fun decide(url: String): Boolean {
                    if (decided || url == "about:blank") return false
                    decided = true
                    scope.launch(Dispatchers.Main) { dismiss() }
                    if (closed || tab !in tabs) return true
                    val navigation = BrowserPolicy.navigation(url)
                    when {
                        navigation == BrowserPolicy.Navigation.CAPTURE -> captureLink(tab.id, url)
                        navigation != BrowserPolicy.Navigation.LOAD -> if (!blockPopups || isUserGesture) route(tab, url)
                        BrowserPolicy.popup(opener, url, isUserGesture, blockPopups) == BrowserPolicy.Popup.TAB ->
                            newTab(url, BrowserPolicy.origin(opener), tab)
                        else -> block(tab, opener, url, popup = true)
                    }
                    return true
                }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = decide(request.url.toString())
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { url?.let { decide(it) } }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                scope.launch(Dispatchers.Main) { delay(POPUP_WAIT_MS); dismiss() }
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

    /** True when the main-frame navigation may load; a blocked one keeps the page and shows the bar. */
    private fun guard(tab: BrowserTab, page: String?, url: String, redirect: Boolean, gesture: Boolean): Boolean {
        val decision = BrowserPolicy.guard(page, url, redirect, gesture, tab.userChain, tab.tap?.fresh(), blockRedirects, redirectSites)
        when (decision) {
            BrowserPolicy.Guard.USER -> { tab.tap = null; tab.userChain = true }
            BrowserPolicy.Guard.LOAD -> if (!redirect) tab.userChain = false
            BrowserPolicy.Guard.BLOCK -> block(tab, page, url, popup = false)
        }
        return decision != BrowserPolicy.Guard.BLOCK
    }

    private fun block(tab: BrowserTab, page: String?, url: String, popup: Boolean) {
        val host = url.toHttpUrlOrNull()?.host ?: return
        notice = GuardNotice(tab.id, url, host, BrowserPolicy.site(page), BrowserPolicy.origin(page), popup)
    }

    private fun recordTaps(tab: BrowserTab) {
        if (closed || tab.tapKey.isEmpty() || tab.webView.url?.toHttpUrlOrNull() == null) return
        tab.webView.evaluateJavascript(TapRecorder.script(tab.tapKey), null)
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
        val source = generateSequence(tabs.find { it.id == next.tabId }) { child -> tabs.find { it.id == child.openerId } }.take(8)
        if (listener && target != null && source.any { it.id == refreshTabId } && next.url.toHttpUrlOrNull() != null &&
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
        const val MAX_POPUPS = 4
        const val POPUP_WAIT_MS = 15_000L
        const val SAVE_INTERVAL_MS = 1_000L
        const val PAUSE_MEDIA = "document.querySelectorAll('video,audio').forEach(function(m){m.pause()})"
        const val STOP_MEDIA = "document.querySelectorAll('video,audio').forEach(function(m){m.pause();m.removeAttribute('src');" +
            "m.querySelectorAll('source').forEach(function(s){s.remove()});m.load()})"
    }
}