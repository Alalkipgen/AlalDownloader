package com.alal.downloader.core.engine

import java.math.BigDecimal
import java.math.RoundingMode

/** The app's single 1024-based size and speed format with B, KB, MB and GB labels. */
object ByteFormat {
    private const val KB = 1024L
    private const val MB = KB * 1024
    private const val GB = MB * 1024

    fun size(bytes: Long): String = when {
        bytes < 0 -> "Unknown"
        bytes < KB -> "$bytes B"
        bytes < MB -> "${scaled(bytes, KB, 1)} KB"
        bytes < GB -> "${scaled(bytes, MB, 1)} MB"
        else -> "${scaled(bytes, GB, 2)} GB"
    }

    fun speed(bytesPerSecond: Long): String {
        val value = bytesPerSecond.coerceAtLeast(0)
        return when {
            value < MB -> "${value / KB} KB/s"
            value < GB -> "${scaled(value, MB, 2)} MB/s"
            else -> "${scaled(value, GB, 2)} GB/s"
        }
    }

    private fun scaled(value: Long, unit: Long, digits: Int): String =
        BigDecimal.valueOf(value).divide(BigDecimal.valueOf(unit), digits, RoundingMode.DOWN).toPlainString()
}
