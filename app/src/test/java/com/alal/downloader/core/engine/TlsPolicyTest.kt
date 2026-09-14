package com.alal.downloader.core.engine

import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TlsPolicyTest {
    @Before fun start() = TlsPolicy.reset()
    @After fun finish() = TlsPolicy.reset()

    @Test fun hostsAreValidatedUntilTheUserOptsOut() {
        assertFalse(TlsPolicy.isInsecure("mmunicode.org.mm"))
        assertFalse(TlsPolicy.isInsecure(null))
        TlsPolicy.allowInsecure(" MMUnicode.org.mm. ")
        assertTrue(TlsPolicy.isInsecure("mmunicode.org.mm"))
        assertTrue(TlsPolicy.isInsecure("MMUNICODE.ORG.MM"))
        assertFalse(TlsPolicy.isInsecure("github.com"))
        assertEquals(setOf("mmunicode.org.mm"), TlsPolicy.insecureHosts.value)
        TlsPolicy.requireSecure("mmunicode.org.mm")
        assertFalse(TlsPolicy.isInsecure("mmunicode.org.mm"))
    }

    @Test fun certificateFailuresAreRecognisedAnywhereInTheCauseChain() {
        val wrapped = DownloadError.Network("SSLPeerUnverifiedException: Hostname mmunicode.org.mm not verified (mmunicode.org.mm)")
            .apply { initCause(SSLPeerUnverifiedException("Hostname mmunicode.org.mm not verified")) }
        assertTrue(TlsPolicy.isCertificateFailure(wrapped))
        assertTrue(TlsPolicy.isCertificateFailure(SSLHandshakeException("handshake aborted")))
        assertFalse(TlsPolicy.isCertificateFailure(DownloadError.Network("refused").apply { initCause(ConnectException("refused")) }))
        assertFalse(TlsPolicy.isCertificateFailure(DownloadError.NotFound()))
    }

    @Test fun insecureClientAcceptsAnyHostnameAndSharesThePool() {
        val base = OkHttpClient()
        val insecure = TlsPolicy.insecure(base)
        assertTrue(insecure.hostnameVerifier.verify("mmunicode.org.mm", null))
        assertNotSame(base.sslSocketFactory, insecure.sslSocketFactory)
        assertSame(base.connectionPool, insecure.connectionPool)
        assertSame(base.dispatcher, insecure.dispatcher)
        assertEquals(base.connectTimeoutMillis, insecure.connectTimeoutMillis)
    }

    @Test fun changesAreWrittenToTheAttachedStore() {
        var saved: Set<String>? = null
        TlsPolicy.attach(object : TlsPolicy.Store {
            override fun load(): Set<String> = setOf("Old.Example.com", "")
            override fun save(hosts: Set<String>) { saved = hosts }
        })
        assertTrue(TlsPolicy.isInsecure("old.example.com"))
        assertEquals(setOf("old.example.com"), TlsPolicy.insecureHosts.value)
        TlsPolicy.allowInsecure("new.example.com")
        assertEquals(setOf("old.example.com", "new.example.com"), saved)
        TlsPolicy.clear()
        assertEquals(emptySet<String>(), saved)
    }
}
