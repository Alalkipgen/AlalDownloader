package com.alal.downloader.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boundary and coverage checks for inclusive segment planning. */
class SegmentPlannerTest {
    private val planner = SegmentPlanner()

    @Test fun defaultSplitsIntoEightBalancedSegments() {
        val segments = planner.plan(103, true)
        assertEquals(8, segments.size)
        assertEquals(103L, segments.sumOf { it.length })
        assertEquals(0L, segments.first().start)
        assertEquals(102L, segments.last().end)
        segments.zipWithNext().forEach { (left, right) -> assertEquals(left.end + 1, right.start) }
        assertTrue(segments.maxOf { it.length } - segments.minOf { it.length } <= 1)
    }

    @Test fun tinyFileHasNoEmptySegments() {
        assertEquals(listOf(Segment(0, 0, 0), Segment(1, 1, 1)), planner.plan(2, true, 32))
    }

    @Test fun emptyFileHasNoSegments() { assertTrue(planner.plan(0, true).isEmpty()) }

    @Test fun unknownAndNonRangeFilesUseOneSegment() {
        assertEquals(listOf(Segment(0, 0, -1)), planner.plan(-1, true))
        assertEquals(listOf(Segment(0, 0, 99)), planner.plan(100, false))
    }

    @Test fun maximumLongDoesNotOverflow() {
        val segments = planner.plan(Long.MAX_VALUE, true, 32)
        assertEquals(Long.MAX_VALUE, segments.sumOf { it.length })
        assertEquals(Long.MAX_VALUE - 1, segments.last().end)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroCountIsRejected() { planner.plan(10, true, 0) }

    @Test(expected = IllegalArgumentException::class)
    fun excessiveCountIsRejected() { planner.plan(10, true, 33) }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSizeIsRejected() { planner.plan(-2, false) }
}