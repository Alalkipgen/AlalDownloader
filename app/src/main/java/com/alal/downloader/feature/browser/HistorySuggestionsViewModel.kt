package com.alal.downloader.feature.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Owns cancellable address suggestions independently of field recomposition. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistorySuggestionsViewModel(application: Application) : AndroidViewModel(application) {
    private val history = application.historyRepository()
    private val input = MutableStateFlow("")
    val suggestions = input.suggestionQueries()
        .flatMapLatest { if (it.isEmpty()) flowOf(emptyList()) else history.suggestions(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(text: String) { input.value = text }
}
