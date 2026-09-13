package com.alal.downloader.feature.browser

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class HistoryPolicyTest {
    @Test fun excludesInternalAndUnsupportedUrls() {
        listOf("about:blank", "data:text/html,hello", "blob:https://example.com/id", "javascript:alert(1)",
            "file:///sdcard/a", "magnet:?xt=urn:btih:abc", "ftp://example.com", "https://", "not a url", "").forEach {
            assertFalse(it, HistoryPolicy.recordable(it))
        }
    }

    @Test fun excludesOnlyExactGoogleHome() {
        assertFalse(HistoryPolicy.recordable("https://www.google.com/"))
        listOf("https://www.google.com/search?q=history", "https://www.google.com/?q=x", "http://example.com/a", "https://example.com/").forEach {
            assertTrue(it, HistoryPolicy.recordable(it))
        }
    }

    @Test fun groupsAcrossYangonMidnightRatherThanUtcMidnight() {
        val zone = TimeZone.getTimeZone("Asia/Yangon")
        val now = Instant.parse("2026-09-13T18:00:00Z").toEpochMilli()
        assertEquals("TODAY", HistoryPolicy.group(Instant.parse("2026-09-13T17:30:00Z").toEpochMilli(), now, zone))
        assertEquals("YESTERDAY", HistoryPolicy.group(Instant.parse("2026-09-13T17:29:59Z").toEpochMilli(), now, zone))
        assertEquals("12 SEP 2026", HistoryPolicy.group(Instant.parse("2026-09-12T17:29:59Z").toEpochMilli(), now, zone))
        assertEquals(Instant.parse("2026-09-13T17:30:00Z").toEpochMilli(), HistoryPolicy.startOfDay(now, zone))
    }

    @Test fun groupingCrossesYearBoundary() {
        val zone = TimeZone.getTimeZone("Asia/Yangon")
        val now = Instant.parse("2025-12-31T18:00:00Z").toEpochMilli()
        assertEquals("YESTERDAY", HistoryPolicy.group(Instant.parse("2025-12-31T17:00:00Z").toEpochMilli(), now, zone))
    }

    @Test fun suggestionsRankCountThenRecencyAndNeverExceedSix() {
        val entries = listOf(2 to 90L, 3 to 10L, 3 to 30L, 1 to 100L, 4 to 1L, 2 to 80L, 1 to 99L, 1 to 98L)
        val expected = listOf(4 to 1L, 3 to 30L, 3 to 10L, 2 to 90L, 2 to 80L, 1 to 100L)
        assertEquals(expected, HistoryPolicy.suggestions(entries, { it.first }, { it.second }))
        assertEquals(expected, HistoryPolicy.suggestions(entries, { it.first }, { it.second }, 100))
        assertEquals(expected.take(2), HistoryPolicy.suggestions(entries, { it.first }, { it.second }, 2))
        assertTrue(HistoryPolicy.suggestions(entries, { it.first }, { it.second }, 0).isEmpty())
    }
}