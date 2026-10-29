package com.alal.downloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.alal.downloader.core.engine.RangeProbe
import com.alal.downloader.feature.browser.BrowserSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import okhttp3.OkHttpClient

/** Keeps the browser session alive until its owning Activity is permanently finished. */
@HiltViewModel
class BrowserHostViewModel @Inject constructor(application: Application, client: OkHttpClient) : AndroidViewModel(application) {
    val session = BrowserSession(application) { RangeProbe(client).probe(it) }.apply { visible = false }

    override fun onCleared() {
        session.close()
    }
}
