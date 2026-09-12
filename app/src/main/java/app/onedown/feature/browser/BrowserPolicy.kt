package app.onedown.feature.browser

import app.onedown.core.engine.DownloadState
import java.io.OutputStream
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Pure URL, filename and refresh matching rules shared by browser capture paths. */
object BrowserPolicy {
    const val HOME = "https://duckduckgo.com/"
    const val EXTENSIONS = "zip rar 7z tar gz bz2 xz mp4 mkv webm avi mov apk apks pdf iso exe msi mp3 m4a flac wav ogg epub mobi doc docx xls xlsx ppt pptx"
    private val mediaExtensions = setOf("mp4", "mkv", "webm", "mp3", "m4a", "flac", "wav", "ogg", "m3u8", "mpd")

    fun address(input: String): String {
        val value = input.trim()
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            return value.toHttpUrlOrNull()?.toString() ?: search(value)
        }
        if (value.startsWith("magnet:", true)) return value
        if (value.none { it.isWhitespace() } && !value.contains("://")) {
            val candidate = "https://$value".toHttpUrlOrNull()
            if (candidate != null && (candidate.host.contains('.') || candidate.host == "localhost")) return candidate.toString()
        }
        return search(value)
    }

    private fun search(value: String) = HOME + "?q=" + URLEncoder.encode(value, "UTF-8")

    fun sanitize(value: String): String = app.onedown.core.engine.FileNames.sanitize(value)

    fun extension(url: String): String = url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()
        ?.substringAfterLast('.', "")?.lowercase().orEmpty()

    fun downloadable(url: String, extensions: String): Boolean = extension(url).let { suffix ->
        suffix.isNotEmpty() && suffix in extensions.lowercase().split(Regex("[\\s,;]+" )).map { it.trimStart('.') }
    }

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