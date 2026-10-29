package com.alal.downloader.core.engine

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Redirect hops keep captured credentials on the original host only. */
class RedirectHeadersTest {
    private val captured = listOf("Cookie" to "session=origin", "Authorization" to "Bearer origin",
        "Proxy-Authorization" to "Basic proxy", "User-Agent" to "browser", "Accept" to "*/*",
        "Referer" to "https://files.example.com/page", "Range" to "bytes=0-511", "If-Range" to "\"v1\"")
    private val transfer = captured.drop(3)

    @Test fun sameHostKeepsCapturedHeadersWithoutCookieLookup() {
        val untouched = CookieSource { error("browser cookies must not be read for the same host") }
        assertEquals(captured, RedirectHeaders.forHop(captured, "https://files.example.com/a", "https://files.example.com/b?x=1", untouched))
        assertEquals(captured, RedirectHeaders.forHop(captured, "https://files.example.com/a", "https://FILES.example.com/c", untouched))
    }

    @Test fun subdomainIsAnotherHost() {
        val hop = RedirectHeaders.forHop(captured, "https://example.com/a", "https://dl.example.com/b", CookieSource { "dl=browser" })
        assertEquals(transfer + ("Cookie" to "dl=browser"), hop)
        val parent = RedirectHeaders.forHop(captured, "https://dl.example.com/a", "https://example.com/b", CookieSource.None)
        assertEquals(transfer, parent)
    }

    @Test fun otherSiteGetsOnlyBrowserCookiesForTheNewUrl() {
        val asked = mutableListOf<String>()
        val hop = RedirectHeaders.forHop(captured, "https://files.example.com/a", "https://cdn.example.net/file.mp4",
            CookieSource { asked.add(it); "cdn=browser" })
        assertEquals(transfer + ("Cookie" to "cdn=browser"), hop)
        assertEquals(listOf("https://cdn.example.net/file.mp4"), asked)
    }

    @Test fun otherSiteWithoutBrowserCookiesSendsNoCookie() {
        val mixedCase = listOf("cookie" to "a=1", "AUTHORIZATION" to "x", "proxy-authorization" to "y", "Range" to "bytes=0-")
        for (source in listOf(CookieSource.None, CookieSource { " " })) {
            assertEquals(listOf("Range" to "bytes=0-"),
                RedirectHeaders.forHop(mixedCase, "https://files.example.com/a", "https://other.example.org/f", source))
        }
    }

    @Test fun redirectChainSendsCookiesOnlyWhereTheyBelong() = runBlocking {
        val seen = mutableListOf<List<String?>>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            seen.add(listOf(request.url.host, request.header("Cookie"), request.header("Authorization"),
                request.header("User-Agent"), request.header("Range")))
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            when (request.url.encodedPath) {
                "/start" -> response.code(302).message("Found").header("Location", "https://cdn.example.net/file.bin")
                "/file.bin" -> response.code(302).message("Found").header("Location", "https://files.example.com/final")
                "/plain" -> response.code(302).message("Found").header("Location", "http://cdn.example.net/file.bin")
                else -> response.code(206).message("Partial Content").header("Content-Range", "bytes 0-511/4096")
            }.body(ByteArray(if (request.url.encodedPath == "/final") 512 else 0).toResponseBody()).build()
        }.build()
        fun request(path: String) = DownloadRequest("https://files.example.com$path", "file.bin", targetDir = File("."),
            headers = mapOf("Cookie" to "session=origin", "Authorization" to "Bearer origin", "User-Agent" to "browser"))
        try {
            DownloadHttp(client) { if (it.startsWith("https://cdn.example.net/")) "cdn=browser" else null }
                .execute(request("/start"), "GET", "bytes=0-511").use { assertEquals(206, it.code) }
            assertEquals(listOf(
                listOf("files.example.com", "session=origin", "Bearer origin", "browser", "bytes=0-511"),
                listOf("cdn.example.net", "cdn=browser", null, "browser", "bytes=0-511"),
                listOf("files.example.com", "session=origin", "Bearer origin", "browser", "bytes=0-511")), seen)
            seen.clear()
            DownloadHttp(client).execute(request("/start"), "GET", "bytes=0-511").close()
            assertNull(seen[1][1])
            assertNull(seen[1][2])
            try {
                DownloadHttp(client).execute(request("/plain"), "GET").close()
                fail("https to http redirect must be rejected")
            } catch (rejected: DownloadError.Unknown) {
                assertTrue(rejected.message.orEmpty(), rejected.message.orEmpty().contains("Insecure redirect"))
            }
        } finally { client.dispatcher.executorService.shutdownNow() }
    }
}
