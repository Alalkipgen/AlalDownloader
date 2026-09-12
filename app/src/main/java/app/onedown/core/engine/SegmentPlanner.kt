package app.onedown.core.engine

/** Divides known file sizes into contiguous, non-overlapping inclusive ranges. */
class SegmentPlanner {
    fun plan(totalBytes: Long, acceptsRanges: Boolean, count: Int = 8): List<Segment> {
        require(count in 1..32) { "Segment count must be 1..32" }
        require(totalBytes >= -1) { "Invalid file length" }
        if (totalBytes == 0L) return emptyList()
        if (!acceptsRanges || totalBytes == -1L) return listOf(Segment(0, 0, if (totalBytes < 0) -1 else totalBytes - 1))
        val size = minOf(count.toLong(), totalBytes).toInt()
        val base = totalBytes / size
        val extra = totalBytes % size
        var offset = 0L
        return List(size) { index ->
            val length = base + if (index < extra) 1 else 0
            Segment(index, offset, offset + length - 1).also { offset += length }
        }
    }
}