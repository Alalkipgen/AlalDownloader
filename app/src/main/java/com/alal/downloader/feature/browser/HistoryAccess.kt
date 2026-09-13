package com.alal.downloader.feature.browser

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Exposes the injected repository to the existing non-injected browser session. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface HistoryAccess {
    fun historyRepository(): HistoryRepository
}

fun Context.historyRepository(): HistoryRepository =
    EntryPointAccessors.fromApplication(applicationContext, HistoryAccess::class.java).historyRepository()