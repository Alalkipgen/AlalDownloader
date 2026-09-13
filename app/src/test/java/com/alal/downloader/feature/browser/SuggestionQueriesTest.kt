package com.alal.downloader.feature.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SuggestionQueriesTest {
    @Test fun waits150msAndOnlyQueriesLatestInput() = runTest {
        val input = MutableStateFlow("")
        val queries = mutableListOf<String>()
        backgroundScope.launch { input.suggestionQueries().collect { if (it.isNotEmpty()) queries += it } }
        runCurrent()
        input.value = "go"
        runCurrent()
        advanceTimeBy(149)
        assertEquals(emptyList<String>(), queries)
        input.value = "goo"
        runCurrent()
        advanceTimeBy(149)
        assertEquals(emptyList<String>(), queries)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("goo"), queries)
    }

    @Test fun shortOrDismissedInputCancelsPendingQueryAndClearsResults() = runTest {
        val input = MutableStateFlow("ab")
        val emissions = mutableListOf<String>()
        backgroundScope.launch { input.suggestionQueries().collect { emissions += it } }
        runCurrent()
        advanceTimeBy(100)
        input.value = " a "
        runCurrent()
        advanceTimeBy(200)
        assertEquals(listOf("", ""), emissions)
        input.value = " ab "
        runCurrent()
        advanceTimeBy(150)
        runCurrent()
        assertEquals("ab", emissions.last())
        input.value = ""
        runCurrent()
        assertEquals("", emissions.last())
    }
}
