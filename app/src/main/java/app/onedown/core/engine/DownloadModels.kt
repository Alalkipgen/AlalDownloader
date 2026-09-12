package app.onedown.core.engine

import java.io.File
import java.io.IOException

/** Immutable input to a transfer, independent of Android presentation APIs. */
data class DownloadRequest(
    val url: String,
    val fileName: String,
    val headers: Map<String, String> = emptyMap(),
    val referrerPageUrl: String? = null,
    val targetDir: File,
    val destinationKind: String = "file",
    val treeUri: String? = null,
    val segmentCount: Int? = null,
    val preserveFileName: Boolean = false,
)

/** Persistable lifecycle of a download. */
enum class DownloadStatus { QUEUED, RUNNING, WAITING_FOR_NETWORK, PAUSED, COMPLETED, FAILED, CANCELLED }

/** Inclusive segment boundaries and durable bytes written within them. */
data class Segment(val index: Int, val start: Long, val end: Long, val downloaded: Long = 0) {
    val length: Long get() = if (end < 0) -1 else end - start + 1
}

/** Metadata describing the selected remote representation. */
data class ProbeResult(
    val totalBytes: Long,
    val acceptsRanges: Boolean,
    val eTag: String?,
    val lastModified: String?,
    val finalUrl: String,
    val fileName: String?,
)

/** Observable and persistable snapshot of one transfer. */
data class DownloadState(
    val id: String,
    val request: DownloadRequest,
    val fileName: String = request.fileName,
    val totalBytes: Long = -1,
    val segments: List<Segment> = emptyList(),
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val eTag: String? = null,
    val lastModified: String? = null,
    val finalUrl: String = request.url,
    val acceptsRanges: Boolean = false,
    val speedBytesPerSecond: Long = 0,
    val error: DownloadError? = null,
    val destinationUri: String? = null,
) {
    val downloadedBytes: Long get() = segments.sumOf { it.downloaded }
}

/** Failures actionable by the download UI without inspecting exception text. */
sealed class DownloadError(message: String) : Exception(message) {
    class Network(message: String) : DownloadError(message)
    class Forbidden : DownloadError("HTTP 403: access forbidden")
    class NotFound : DownloadError("HTTP 404: file not found")
    class RangeNotSatisfiable : DownloadError("HTTP 416: range not satisfiable")
    class LinkExpired : DownloadError("HTTP 410: link expired")
    class DiskFull : DownloadError("Insufficient disk space")
    class Unknown(message: String) : DownloadError(message)
}

/** Persistence boundary implemented by the data layer. */
interface DownloadStore {
    suspend fun load(): List<DownloadState>
    suspend fun save(state: DownloadState)
    suspend fun delete(id: String)
}

internal class RangeFallback : Exception()

/** Whether a saved request can obtain a fresh signed link from its source page. */
fun DownloadState.canRefreshLink(): Boolean = request.referrerPageUrl != null &&
    status in setOf(DownloadStatus.FAILED, DownloadStatus.PAUSED) &&
    (error is DownloadError.Forbidden || error is DownloadError.NotFound || error is DownloadError.LinkExpired)

internal fun Throwable.asDownloadError(): DownloadError = when {
    this is DownloadError -> this
    generateSequence(this) { it.cause }.any {
        it.message.orEmpty().contains("ENOSPC", true) ||
            it.message.orEmpty().contains("No space left", true)
    } -> DownloadError.DiskFull()
    this is IOException -> DownloadError.Network(message ?: "Network I/O failed")
    else -> DownloadError.Unknown(message ?: javaClass.simpleName)
}