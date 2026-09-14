package com.alal.downloader.feature.downloads

import com.alal.downloader.core.engine.*
import java.net.ConnectException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddDownloadViewModelTest {
    @Before fun start() = TlsPolicy.reset()
    @After fun finish() = TlsPolicy.reset()

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
        assertFalse(model.state.value.insecure)
        assertTrue(model.state.value.probed)
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
        assertNull(model.state.value.insecureHost)
    }

    @Test fun certificateFailureOffersIgnoreAndReconnects() = runTest {
        var attempts = 0
        val model = AddDownloadViewModel(DownloadProber { request ->
            attempts++
            if (!TlsPolicy.isInsecure("mmunicode.org.mm")) {
                throw DownloadError.Network("SSLPeerUnverifiedException: Hostname mmunicode.org.mm not verified (mmunicode.org.mm)")
                    .apply { initCause(SSLPeerUnverifiedException("Hostname mmunicode.org.mm not verified")) }
            }
            ProbeResult(18010450, true, null, null, request.url, "All-in-One_Pyidaungsu_Font.zip")
        }, StandardTestDispatcher(testScheduler))
        model.link("https://mmunicode.org.mm/downloads/zips/All-in-One_Pyidaungsu_Font.zip")
        model.probe("UA")
        assertEquals("mmunicode.org.mm", model.state.value.insecureHost)
        assertTrue(model.state.value.error.orEmpty().startsWith("Certificate error"))
        assertTrue(model.state.value.error.orEmpty().contains("Hostname mmunicode.org.mm not verified"))
        assertFalse(model.state.value.probed)
        assertFalse(model.state.value.insecure)

        model.probeIgnoringCertificate("UA")
        assertEquals(2, attempts)
        assertTrue(TlsPolicy.isInsecure("mmunicode.org.mm"))
        assertNull(model.state.value.error)
        assertNull(model.state.value.insecureHost)
        assertTrue(model.state.value.insecure)
        assertTrue(model.state.value.probed)
        assertEquals(18010450L, model.state.value.size)
        assertEquals("All-in-One_Pyidaungsu_Font", model.state.value.name)
        assertEquals("zip", model.state.value.extension)

        model.restoreCertificateCheck()
        assertFalse(TlsPolicy.isInsecure("mmunicode.org.mm"))
        assertFalse(model.state.value.insecure)
        assertNull(model.state.value.size)
        assertEquals("https://mmunicode.org.mm/downloads/zips/All-in-One_Pyidaungsu_Font.zip", model.state.value.link)
    }

    @Test fun plainNetworkFailureDoesNotOfferToIgnoreCertificates() = runTest {
        val model = AddDownloadViewModel(DownloadProber {
            throw DownloadError.Network("ConnectException: Connection refused (example.com)").apply { initCause(ConnectException("Connection refused")) }
        }, StandardTestDispatcher(testScheduler))
        model.link("example.com")
        model.probe("UA")
        assertNull(model.state.value.insecureHost)
        assertFalse(model.state.value.insecure)
        assertEquals("ConnectException: Connection refused (example.com)", model.state.value.error)
    }
}
