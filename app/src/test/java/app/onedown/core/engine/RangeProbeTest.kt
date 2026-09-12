package app.onedown.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parsing tests for range metadata and server-provided filenames. */
class RangeProbeTest {
    @Test fun parsesKnownAndUnknownTotals() {
        assertEquals(RangeProbe.ContentRange(0, 0, 100), RangeProbe.parseContentRange("bytes 0-0/100"))
        assertEquals(RangeProbe.ContentRange(10, 19, -1), RangeProbe.parseContentRange(" bytes 10-19/* "))
    }

    @Test fun rejectsMalformedAndOverflowingRanges() {
        listOf(null, "", "bytes */100", "items 0-9/10", "bytes 9-0/10", "bytes 0-10/10",
            "bytes 0-0/0", "bytes 0-9223372036854775808/*", "bytes -1-0/10").forEach {
            assertNull(it, RangeProbe.parseContentRange(it))
        }
    }

    @Test fun parsesPlainAndQuotedNames() {
        assertEquals("file.zip", RangeProbe.parseContentDisposition("attachment; filename=file.zip"))
        assertEquals("a;b.zip", RangeProbe.parseContentDisposition("attachment; filename=\"a;b.zip\""))
    }

    @Test fun prefersExtendedUtf8AndPreservesPlus() {
        assertEquals("€+file.zip", RangeProbe.parseContentDisposition(
            "attachment; filename=old.zip; filename*=UTF-8''%E2%82%AC+file.zip"))
    }

    @Test fun invalidExtendedEncodingFallsBack() {
        assertEquals("old.zip", RangeProbe.parseContentDisposition("attachment; filename=old.zip; filename*=UTF-8''%ZZ"))
    }

    @Test fun stripsTraversalAndRejectsEmptyNames() {
        assertEquals("file.zip", RangeProbe.parseContentDisposition("attachment; filename=../../file.zip"))
        assertNull(RangeProbe.parseContentDisposition("attachment; filename=\"\""))
        assertNull(RangeProbe.parseContentDisposition(null))
        assertNull(RangeProbe.parseContentDisposition("attachment; filename=.."))
    }
}