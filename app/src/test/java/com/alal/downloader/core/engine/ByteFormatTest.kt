package com.alal.downloader.core.engine

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** One 1024-based size and speed format shared by notifications, list, details and browser. */
class ByteFormatTest {
    private val kb = 1024L
    private val mb = kb * 1024
    private val gb = mb * 1024

    @Test fun sizesKeepTodaysPrecisionWithNewLabels() {
        assertEquals("Unknown", ByteFormat.size(-1))
        assertEquals("0 B", ByteFormat.size(0))
        assertEquals("1023 B", ByteFormat.size(kb - 1))
        assertEquals("1.0 KB", ByteFormat.size(kb))
        assertEquals("1.5 KB", ByteFormat.size(kb + 512))
        assertEquals("1023.9 KB", ByteFormat.size(mb - 1))
        assertEquals("1.0 MB", ByteFormat.size(mb))
        assertEquals("17.1 MB", ByteFormat.size(18_010_450))
        assertEquals("1023.9 MB", ByteFormat.size(gb - 1))
        assertEquals("1.00 GB", ByteFormat.size(gb))
        assertEquals("1.50 GB", ByteFormat.size(gb + gb / 2))
    }

    @Test fun speedsAreWholeKbBelowOneMbAndTwoDecimalsFromOneMb() {
        assertEquals("0 KB/s", ByteFormat.speed(-1))
        assertEquals("0 KB/s", ByteFormat.speed(0))
        assertEquals("0 KB/s", ByteFormat.speed(kb - 1))
        assertEquals("1 KB/s", ByteFormat.speed(kb))
        assertEquals("850 KB/s", ByteFormat.speed(850 * kb))
        assertEquals("1023 KB/s", ByteFormat.speed(mb - 1))
        assertEquals("1.00 MB/s", ByteFormat.speed(mb))
        assertEquals("1.25 MB/s", ByteFormat.speed(mb + mb / 4))
        assertEquals("1023.99 MB/s", ByteFormat.speed(gb - 1))
        assertEquals("1.00 GB/s", ByteFormat.speed(gb))
    }

    @Test fun unitBoundariesNeverShow1024OfTheSmallerUnit() {
        for (unit in listOf(kb, mb, gb)) for (value in unit - 3..unit + 3) {
            for (text in listOf(ByteFormat.size(value), ByteFormat.speed(value))) assertFalse(text, text.startsWith("1024"))
        }
    }

    @Test fun formatIgnoresDeviceLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.25 MB/s", ByteFormat.speed(mb + mb / 4))
            assertEquals("1.5 KB", ByteFormat.size(kb + 512))
        } finally { Locale.setDefault(saved) }
    }
}
