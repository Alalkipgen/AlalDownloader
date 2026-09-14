package com.alal.downloader.core.engine

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * Hosts the user explicitly excluded from TLS certificate validation (1DM's "ignore SSL errors").
 *
 * Misconfigured servers (e.g. a custom domain fronted by GitHub Pages that still serves the *.github.io
 * certificate) fail with SSLPeerUnverifiedException in every stock HTTP client. The user can opt a single host
 * out; every request to that host - Connect probe, engine probe and all segments - then uses a trust-all client.
 */
object TlsPolicy {
    /** Persistence boundary implemented by the data layer. */
    interface Store {
        fun load(): Set<String>
        fun save(hosts: Set<String>)
    }

    private val mutableHosts = MutableStateFlow<Set<String>>(emptySet())
    /** Lower-cased hosts whose certificates are not validated. */
    val insecureHosts = mutableHosts.asStateFlow()
    private var store: Store? = null

    /** Installs persistence and loads the remembered hosts. */
    @Synchronized fun attach(store: Store) {
        this.store = store
        mutableHosts.value = store.load().map(::normalize).filter { it.isNotEmpty() }.toSet()
    }

    fun isInsecure(host: String?): Boolean = !host.isNullOrBlank() && normalize(host) in mutableHosts.value

    @Synchronized fun allowInsecure(host: String) {
        val key = normalize(host)
        require(key.isNotEmpty()) { "Host required" }
        update(mutableHosts.value + key)
    }

    @Synchronized fun requireSecure(host: String) {
        update(mutableHosts.value - normalize(host))
    }

    @Synchronized fun clear() {
        update(emptySet())
    }

    /** Forgets every host and detaches persistence; intended for tests. */
    @Synchronized fun reset() {
        store = null
        mutableHosts.value = emptySet()
    }

    /** Whether a failure chain is a TLS trust/hostname problem that ignoring the certificate would bypass. */
    fun isCertificateFailure(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any { it is SSLException }

    private fun update(hosts: Set<String>) {
        mutableHosts.value = hosts
        store?.save(hosts)
    }

    private fun normalize(host: String): String = host.trim().trimEnd('.').lowercase()

    /** A client sharing [base]'s pool and dispatcher that accepts any certificate and hostname. */
    @Suppress("TrustAllX509TrustManager", "CustomX509TrustManager", "BadHostnameVerifier")
    fun insecure(base: OkHttpClient): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
