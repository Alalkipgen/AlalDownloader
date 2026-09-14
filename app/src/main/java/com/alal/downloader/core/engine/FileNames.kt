package com.alal.downloader.core.engine

/** Shared portable filename policy with a UTF-8 byte budget and extension-preserving suffixes. */
object FileNames {
    fun sanitize(value: String): String {
        val cleaned = value.replace('\\', '/').substringAfterLast('/')
            .filter { it >= ' ' && it !in ":*?\"<>|\u007f" && Character.getType(it) != Character.FORMAT.toInt() }
            .trim().trimEnd('.')
        val dot = cleaned.lastIndexOf('.').takeIf { it > 0 } ?: cleaned.length
        val extension = bounded(cleaned.substring(dot), 40)
        return (bounded(cleaned.substring(0, dot), 180 - extension.toByteArray(Charsets.UTF_8).size) + extension)
            .ifBlank { "download" }
    }

    fun numbered(name: String, number: Int): String {
        require(number >= 0)
        val safe = sanitize(name)
        if (number == 0) return safe
        val dot = safe.lastIndexOf('.').takeIf { it > 0 } ?: safe.length
        val extension = bounded(safe.substring(dot), 40)
        val suffix = " ($number)$extension"
        return bounded(safe.substring(0, dot), 180 - suffix.toByteArray(Charsets.UTF_8).size) + suffix
    }

    private fun bounded(value: String, budget: Int): String {
        val result = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            val text = String(Character.toChars(point))
            val count = text.toByteArray(Charsets.UTF_8).size
            if (bytes + count > budget) break
            result.append(text)
            bytes += count
            index += Character.charCount(point)
        }
        return result.toString()
    }
}