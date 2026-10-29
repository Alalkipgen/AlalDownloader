package com.alal.downloader.feature.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The http(s) links in text shared to the app; apps send "title + link", often with punctuation around it. */
object SharedLinks {
    private const val MAX_LINKS = 1000
    private val candidate = Regex("""https?://[^\s\p{Z}\p{Cc}\u200B-\u200D\u2060\uFEFF<>"“”‘’«»「」『』（）【】]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?'…。、，！？။၊"
    private val brackets = mapOf(')' to '(', ']' to '[', '}' to '{')

    /** Distinct links in order; a closing bracket stays only when the link itself opened one. */
    fun extract(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return candidate.findAll(text).mapNotNull { trim(it.value).toHttpUrlOrNull()?.toString() }
            .distinct().take(MAX_LINKS).toList()
    }

    private fun trim(match: String): String {
        var link = match
        while (link.isNotEmpty()) {
            val last = link.last()
            val open = brackets[last]
            link = when {
                last in TRAILING -> link.dropLast(1)
                open != null && link.count { it == last } > link.count { it == open } -> link.dropLast(1)
                else -> return link
            }
        }
        return link
    }
}
