package com.alal.downloader.feature.downloads

import com.alal.downloader.core.engine.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddDownloadViewModelTest {
    @Test fun connectFillsNameSizeResumeAndFinalUrl() = runTest {
        val model = AddDownloadViewModel(DownloadProber { request ->
            assertEquals("https://example.com/", request.url)
            assertEquals("https://example.com/page", request.referer)
            assertEquals("test UA", request.userAgent)
            ProbeResult(2048, true, null, null, "https://cdn.example.com/file", "real.zip")
        }, StandardTestDispatcher(testScheduler))
        model.link("example.com")
        model.referrer("https://example.com/page")
        model.probe("test UA")
        assertEquals("real", model.state.value.name)
        assertEquals("zip", model.state.value.extension)
        assertEquals(2048L, model.state.value.size)
        assertEquals(true, model.state.value.resume)
        assertEquals("https://cdn.example.com/file", model.state.value.finalUrl)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.error)
    }

    @Test fun htmlDisablesAddUntilLinkChanges() = runTest {
        val model = AddDownloadViewModel(DownloadProber { throw NeedsBrowser(it.url) }, StandardTestDispatcher(testScheduler))
        model.link("www.facebook.com")
        model.probe("UA")
        assertTrue(model.state.value.html)
        assertEquals("This is a web page, not a file", model.state.value.error)
        model.referrer("https://example.com")
        assertTrue(model.state.value.html)
        model.link("https://example.com/file.zip")
        assertFalse(model.state.value.html)
        assertNull(model.state.value.error)
    }

    @Test fun failureDoesNotProduceMetadata() = runTest {
        val model = AddDownloadViewModel(DownloadProber { throw DownloadError.Network("probe failed") }, StandardTestDispatcher(testScheduler))
        model.link("example.com")
        model.probe("UA")
        assertEquals("probe failed", model.state.value.error)
        assertNull(model.state.value.size)
        assertFalse(model.state.value.busy)
    }
}