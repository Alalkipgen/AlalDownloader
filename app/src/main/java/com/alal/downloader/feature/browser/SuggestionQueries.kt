package com.alal.downloader.feature.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/** Cancels stale queries immediately and waits for 150 ms of stable eligible input. */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<String>.suggestionQueries(): Flow<String> = map(String::trim)
    .distinctUntilChanged()
    .transformLatest { query ->
        emit("")
        if (query.length >= 2) {
            delay(150)
            emit(query)
        }
    }
