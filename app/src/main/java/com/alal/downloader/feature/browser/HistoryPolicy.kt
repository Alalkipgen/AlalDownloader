package com.alal.downloader.feature.browser

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pure history eligibility, local-calendar grouping and ranking rules. */
object HistoryPolicy {
    fun recordable(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
            !uri.host.isNullOrEmpty() && url != "https://www.google.com/"
    }.getOrDefault(false)

    fun startOfDay(now: Long, zone: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(zone).apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun group(visitedAt: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val today = startOfDay(now, zone)
        val yesterday = Calendar.getInstance(zone).apply { timeInMillis = today; add(Calendar.DATE, -1) }.timeInMillis
        return when (startOfDay(visitedAt, zone)) {
            today -> "TODAY"
            yesterday -> "YESTERDAY"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).apply { timeZone = zone }.format(Date(visitedAt)).uppercase(Locale.ENGLISH)
        }
    }

    fun <T> suggestions(entries: List<T>, visits: (T) -> Int, time: (T) -> Long, limit: Int = 6): List<T> =
        entries.sortedWith(compareByDescending(visits).thenByDescending(time)).take(limit.coerceIn(0, 6))
}