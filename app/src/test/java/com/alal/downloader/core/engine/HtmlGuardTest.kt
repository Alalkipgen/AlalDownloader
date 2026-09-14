package com.alal.downloader.core.engine

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HtmlGuardTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun htmlWithoutDispositionNeedsBrowser() = assertTrue(HtmlGuard.needsBrowser("text/html", null))
    @Test fun htmlAttachmentIsAllowed() = assertFalse(HtmlGuard.needsBrowser("text/html", "attachment; filename=page.html"))
    @Test fun zipIsAllowed() = assertFalse(HtmlGuard.needsBrowser("application/zip", null))
    @Test fun octetStreamIsAllowed() = assertFalse(HtmlGuard.needsBrowser("application/octet-stream", null))
    @Test fun mixedCaseHtmlAndInlineDispositionNeedBrowser() = assertTrue(HtmlGuard.needsBrowser("Text/HTML; charset=UTF-8", "inline"))
    @Test fun attachmentInFilenameDoesNotBypassGuard() = assertTrue(HtmlGuard.needsBrowser("text/html", "inline; filename=attachment"))
    @Test fun headRejectsPageBeforeCreatingAnyOutput() = rejectedPage(200)
    @Test fun rejectedHeadFallsBackWithHeadersAndRejectsPage() = rejectedPage(405)
    @Test fun forbiddenHeadFallsBackWithHeadersAndRejectsPage() = rejectedPage(403)

    private fun rejectedPage(headCode: Int) = runBlocking {
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("session=secret", request.header("Cookie"))
            assertEquals("https://example.com/page", request.header("Referer"))
            assertEquals("Browser UA", request.header("User-Agent"))
            methods.add(request.method)
            if (request.method == "GET") assertEquals("bytes=0-511", request.header("Range"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Response")
                .code(if (request.method == "HEAD") headCode else 200)
                .header("Content-Type", "text/html; charset=utf-8")
                .body("<html>not a file</html>".toResponseBody()).build()
        }.build()
        try {
            val task = DownloadTask(DownloadState("page", request()), client, Store(), publish = {})
            task.run()
            assertEquals(DownloadStatus.NEEDS_BROWSER, task.state.status)
            assertNull(task.state.error)
            assertEquals(request().url, task.state.finalUrl)
            assertEquals(if (headCode == 200) listOf("HEAD") else listOf("HEAD", "GET"), methods)
            assertTrue(temporary.root.listFiles()!!.isEmpty())
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun headersSurviveEverySegmentAndResumedRequest() = runBlocking {
        val ranges = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("session=secret", request.header("Cookie"))
            assertEquals("https://example.com/page", request.header("Referer"))
            assertEquals("Browser UA", request.header("User-Agent"))
            val range = request.header("Range")
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            val end = (range?.substringAfter('-')?.toInt() ?: 5).coerceAtMost(5)
            if (range != null) ranges.add(range)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK")
                .code(if (range == null) 200 else 206)
                .header("Content-Type", "application/zip").header("Accept-Ranges", "bytes")
                .header("ETag", "\"same\"").header("Content-Length", if (range == null) "6" else "${end - start + 1}")
                .apply { if (range != null) header("Content-Range", "bytes $start-$end/6") }
                .body((if (request.method == "HEAD") "" else "abcdef".substring(start, end + 1)).toResponseBody()).build()
        }.build()
        try {
            val initial = DownloadState("resume", request(), totalBytes = 6, acceptsRanges = true,
                eTag = "\"same\"", segments = listOf(Segment(0, 0, 2, 1), Segment(1, 3, 5, 1)))
            val file = File(File(temporary.root, initial.id).apply { mkdirs() }, initial.fileName)
            file.writeText("axx dxx".replace(" ", ""))
            val task = DownloadTask(initial, client, Store(), publish = {})
            task.run()
            assertEquals(DownloadStatus.COMPLETED, task.state.status)
            assertEquals(setOf("bytes=0-511", "bytes=1-2", "bytes=4-5"), ranges.toSet())
            assertEquals("abcdef", file.readText())
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun transferHtmlCannotWriteBodyAfterBinaryProbe() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("OK").code(200)
                .header("Content-Type", if (request.method == "HEAD") "application/zip" else "text/html")
                .header("Accept-Ranges", "bytes").header("Content-Length", "6")
                .body((if (request.method == "HEAD") "" else "<html>").toResponseBody()).build()
        }.build()
        try {
            val task = DownloadTask(DownloadState("changed", request()), client, Store(), publish = {})
            task.run()
            assertEquals(DownloadStatus.NEEDS_BROWSER, task.state.status)
            assertEquals(0L, task.state.downloadedBytes)
            assertTrue(temporary.root.listFiles()!!.isEmpty())
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun redirectedHtmlIsRejected() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val redirected = chain.request().url.encodedPath == "/page"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Response")
                .code(if (redirected) 200 else 301).header("Location", "/page")
                .header("Content-Type", if (redirected) "text/html" else "application/octet-stream")
                .body("<html>page</html>".toResponseBody()).build()
        }.build()
        try {
            val task = DownloadTask(DownloadState("redirect", request()), client, Store(), publish = {})
            task.run()
            assertEquals(DownloadStatus.NEEDS_BROWSER, task.state.status)
            assertEquals("https://example.com/page", task.state.finalUrl)
            assertTrue(temporary.root.listFiles()!!.isEmpty())
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun octetStreamHtmlIsSniffedAndDeleted() = runBlocking {
        val body = "  <!DOCTYPE html><html>page</html>"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("OK").code(200)
                .header("Content-Type", "application/octet-stream").header("Accept-Ranges", "bytes")
                .header("Content-Length", body.length.toString())
                .body((if (chain.request().method == "HEAD") "" else body).toResponseBody()).build()
        }.build()
        try {
            val task = DownloadTask(DownloadState("sniff", request()), client, Store(), publish = {})
            task.run()
            assertEquals(DownloadStatus.NEEDS_BROWSER, task.state.status)
            assertEquals(0L, task.state.downloadedBytes)
            assertTrue(temporary.root.listFiles()!!.isEmpty())
        } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun explicitlyAttachedHtmlPassesSniffing() {
        HtmlGuard.checkPrefix("https://example.com", "<!DOCTYPE html>".toByteArray(), "attachment; filename=page.html")
    }

    private fun request() = DownloadRequest("https://example.com/file", "file.zip", targetDir = temporary.root,
        cookies = "session=secret", referer = "https://example.com/page", userAgent = "Browser UA")

    private class Store : DownloadStore {
        override suspend fun load() = emptyList<DownloadState>()
        override suspend fun save(state: DownloadState) = Unit
        override suspend fun delete(id: String) = Unit
    }
}