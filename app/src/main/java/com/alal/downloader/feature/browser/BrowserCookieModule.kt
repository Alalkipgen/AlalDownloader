package com.alal.downloader.feature.browser

import android.webkit.CookieManager
import com.alal.downloader.core.engine.CookieSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Gives the engine the WebView cookie jar for redirect hops to another host. */
@Module
@InstallIn(SingletonComponent::class)
object BrowserCookieModule {
    @Provides
    @Singleton
    fun cookies(): CookieSource = CookieSource { url ->
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
