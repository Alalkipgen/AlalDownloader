package com.alal.downloader.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.alal.downloader.feature.browser.HistoryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoryQueriesTest {
    @Test fun searchAndSuggestionsExecuteAndTreatSpecialCharactersLiterally() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java).build()
        try {
            val repository = HistoryRepository(database, context)
            val titles = listOf("Alpha", "Al%literal", "Al_literal", "Al\\literal", "Al!literal", "Al!%_\\literal", "Unrelated")
            titles.forEachIndexed { index, title ->
                database.history().record(HistoryEntry(
                    url = "https://example.com/$index", title = title, host = "example.com", visitedAt = index.toLong(),
                ))
            }
            assertEquals(6, repository.entries("Al").first().size)
            assertEquals(6, repository.suggestions("Al").first().size)
            listOf("Al%", "Al_", "Al\\", "Al!l", "Al!%_\\").forEach { query ->
                val expected = titles.filter { it.startsWith(query) }.toSet()
                assertEquals(query, expected, repository.entries(query).first().map { it.title }.toSet())
                assertEquals(query, expected, repository.suggestions(query).first().map { it.title }.toSet())
            }
            assertEquals(setOf("Al%literal", "Al!%_\\literal"), repository.entries("%").first().map { it.title }.toSet())
            assertTrue(repository.entries("missing").first().isEmpty())
            assertTrue(repository.suggestions("missing").first().isEmpty())
        } finally {
            database.close()
        }
    }
}