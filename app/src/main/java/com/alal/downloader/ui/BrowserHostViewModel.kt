package com.alal.downloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.alal.downloader.feature.browser.BrowserSession

/** Keeps the browser session alive until its owning Activity is permanently finished. */
class BrowserHostViewModel(application: Application) : AndroidViewModel(application) {
    val session = BrowserSession(application).apply { visible = false }

    override fun onCleared() {
        session.close()
    }
}