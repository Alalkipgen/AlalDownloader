package com.alal.downloader.feature.browser

import com.alal.downloader.core.engine.DownloadState
import com.alal.downloader.core.engine.ProbeResult
import java.io.OutputStream
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Pure URL, filename and refresh matching rules shared by browser capture paths. */
object BrowserPolicy {
    const val HOME = "https://www.google.com"
    const val EXTENSIONS = "zip rar 7z tar gz bz2 xz mp4 mkv webm avi mov apk apks pdf iso exe msi mp3 m4a flac wav ogg epub mobi doc docx xls xlsx ppt pptx"
    private val mediaExtensions = setOf("mp4", "mkv", "webm", "mp3", "m4a", "flac", "wav", "ogg", "m3u8", "mpd")

    fun address(input: String): String {
        val value = input.trim()
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            return value.toHttpUrlOrNull()?.toString() ?: search(value)
        }
        if (value.startsWith("magnet:", true)) return value
        if (value.startsWith("javascript:", true) || value.startsWith("data:", true) || 
            value.startsWith("intent:", true) || value.startsWith("mailto:", true) || 
            value.startsWith("tel:", true)) {
            return HOME
        }
        if (value.none { it.isWhitespace() } && !value.contains("://")) {
            val candidate = "https://$value".toHttpUrlOrNull()
            if (candidate != null && (candidate.host.contains('.') || candidate.host == "localhost")) return candidate.toString()
        }
        return search(value)
    }

    private fun search(value: String) = "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8")

    fun sanitize(value: String): String = com.alal.downloader.core.engine.FileNames.sanitize(value)

    fun extension(url: String): String = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
        ?.substringAfterLast('.', "")?.lowercase().orEmpty()

    fun downloadable(url: String, extensions: String): Boolean = extension(url).let { suffix ->
        suffix.isNotEmpty() && suffix in extensions.lowercase().split(Regex("""[\s,;]+""")).map { it.trimStart('.') }
    }

    /** Top-level navigation outcome; a URL's file extension never turns a page load into a download. */
    enum class Navigation { LOAD, CAPTURE, MAGNET, UNSUPPORTED }

    fun navigation(url: String): Navigation = when {
        url.startsWith("magnet:", true) -> Navigation.MAGNET
        url.startsWith("blob:") || url.startsWith("data:") -> Navigation.CAPTURE
        url.toHttpUrlOrNull() == null -> Navigation.UNSUPPORTED
        else -> Navigation.LOAD
    }

    /** Registrable site: OkHttp's top private domain, or the host itself when it has none. */
    fun site(url: String?): String? = url?.toHttpUrlOrNull()?.let { it.topPrivateDomain() ?: it.host }

    fun origin(url: String?): String? = url?.toHttpUrlOrNull()?.newBuilder()?.username("")?.password("")
        ?.encodedPath("/")?.query(null)?.fragment(null)?.build()?.toString()

    /** The tapped link's or form's resolved address and whether the element was visibly tappable. */
    data class Tap(val address: String, val form: Boolean, val visible: Boolean)

    /** Invisible: opacity under 0.1 or under 2 px; full-screen overlay: covering 80% or more of the viewport. */
    fun visible(opacity: Double, width: Double, height: Double, viewportWidth: Double, viewportHeight: Double): Boolean {
        if (!(opacity >= 0.1) || !(width >= 2) || !(height >= 2) || !(viewportWidth > 0) || !(viewportHeight > 0)) return false
        return minOf(width, viewportWidth) * minOf(height, viewportHeight) < 0.8 * viewportWidth * viewportHeight
    }

    /** A link must be the navigation's address (fragment aside); a GET form only its action path, as its fields fill the query. */
    fun tapped(tap: Tap?, target: String): Boolean {
        if (tap == null || !tap.visible) return false
        val address = tap.address.toHttpUrlOrNull()?.newBuilder()?.fragment(null) ?: return false
        val url = target.toHttpUrlOrNull()?.newBuilder()?.fragment(null) ?: return false
        if (tap.form) { address.query(null); url.query(null) }
        return address.build() == url.build()
    }

    /** Main-frame navigation outcome; USER is a user-started load whose server redirects may follow it. */
    enum class Guard { LOAD, USER, BLOCK }

    /**
     * Same-site and user-started navigations load. [page] is the page shown; [redirect] a server redirect, which follows
     * a user-started chain ([userChain]) or a visible form tap whose POST WebView does not report.
     */
    fun guard(page: String?, target: String, redirect: Boolean, gesture: Boolean, userChain: Boolean, tap: Tap?,
        blockRedirects: Boolean, allowedSites: Set<String>): Guard {
        val user = if (redirect) userChain || (tap != null && tap.form && tap.visible) else gesture && tapped(tap, target)
        if (user) return Guard.USER
        val from = site(page)
        return if (!blockRedirects || from == null || from == site(target) || from in allowedSites) Guard.LOAD else Guard.BLOCK
    }

    /** A popup never replaces the page: it opens in a new tab or is blocked. */
    enum class Popup { TAB, BLOCK }

    fun popup(opener: String?, target: String, gesture: Boolean, blockPopups: Boolean): Popup {
        val from = site(opener)
        return if (!blockPopups || (gesture && from != null && from == site(target))) Popup.TAB else Popup.BLOCK
    }

    private val mimeType = Regex("""[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+""")

    fun documentType(result: String?): String? {
        val value = result?.trim()?.takeIf { it.length > 2 && it.startsWith('"') && it.endsWith('"') } ?: return null
        return value.substring(1, value.length - 1).substringBefore(';').trim().lowercase().takeIf { mimeType.matches(it) }
    }

    fun offersDocument(contentType: String?, url: String, extensions: String): Boolean {
        if (url.toHttpUrlOrNull() == null) return false
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        if (type.startsWith("video/") || type.startsWith("audio/")) return true
        return type != "text/html" && type != "application/xhtml+xml" && downloadable(url, extensions)
    }

    fun documentReferrer(history: List<String?>, current: Int, fileUrl: String): String? {
        val file = withoutQuery(fileUrl)
        val start = if (history.getOrNull(current)?.let(::withoutQuery) == file) current - 1 else current
        return (start downTo 0).asSequence().map { history.getOrNull(it) }.firstOrNull { it == null || withoutQuery(it) != file }
            ?.takeIf { it.toHttpUrlOrNull() != null }
    }

    private fun withoutQuery(url: String): String =
        url.toHttpUrlOrNull()?.newBuilder()?.query(null)?.fragment(null)?.build()?.toString() ?: url

    fun documentDetails(probe: ProbeResult?, urlName: String): Pair<String, Long> =
        if (probe == null) urlName to -1L else sanitize(probe.serverFileName ?: urlName) to probe.totalBytes

    fun mediaCandidate(url: String): Boolean = extension(url) in mediaExtensions

    fun matches(target: DownloadState, fileName: String, size: Long): Boolean =
        sanitize(fileName) == sanitize(target.fileName) || (size > 0 && target.totalBytes > 0 && size == target.totalBytes)

    fun replayHeaders(headers: Map<String, String>): Map<String, String> = headers.filterKeys {
        it.lowercase() !in setOf("range", "if-range", "host", "connection", "content-length", "accept-encoding")
    }

    fun decodeData(url: String, output: OutputStream, progress: (Long) -> Unit) {
        require(url.startsWith("data:", true)) { "Not a data URL" }
        val comma = url.indexOf(',')
        require(comma in 5..4096) { "Invalid data URL metadata" }
        val base64 = url.substring(5, comma).split(';').any { it.equals("base64", true) }
        val input = object : java.io.InputStream() {
            var position = comma + 1
            override fun read(): Int {
                if (position >= url.length) return -1
                val char = url[position++]
                if (char != '%') {
                    require(char.code <= 127) { "Non-ASCII data must be percent encoded" }
                    return char.code
                }
                require(position + 1 < url.length) { "Invalid percent escape" }
                val high = requireNotNull(url[position++].digitToIntOrNull(16)) { "Invalid percent escape" }
                val low = requireNotNull(url[position++].digitToIntOrNull(16)) { "Invalid percent escape" }
                return high * 16 + low
            }
        }
        val decoded = if (base64) object : java.io.InputStream() {
            var bits = 0
            var count = 0
            var ended = false
            override fun read(): Int {
                if (ended) return -1
                while (true) {
                    val char = input.read()
                    if (char < 0 || char == '='.code) {
                        require(count != 6 && bits == 0) { "Invalid base64 tail" }
                        if (char == '='.code) {
                            if (count == 4) require(input.read() == '='.code) { "Missing base64 padding" }
                            else require(count == 2) { "Invalid base64 padding" }
                            require(input.read() == -1) { "Trailing base64 data" }
                        }
                        ended = true
                        return -1
                    }
                    val value = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".indexOf(char.toChar())
                    require(value >= 0) { "Invalid base64 character" }
                    bits = (bits shl 6) or value
                    count += 6
                    if (count >= 8) {
                        count -= 8
                        val byte = bits shr count
                        bits = bits and ((1 shl count) - 1)
                        return byte
                    }
                }
            }
        } else input
        decoded.use {
            val buffer = ByteArray(48 * 1024)
            var total = 0L
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                total += count
                progress(total)
            }
        }
    }
}