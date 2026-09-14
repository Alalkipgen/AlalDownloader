package com.alal.downloader.core.data

import com.alal.downloader.core.engine.DownloadError
import com.alal.downloader.core.engine.DownloadRequest
import com.alal.downloader.core.engine.DownloadState
import com.alal.downloader.core.engine.DownloadStatus
import com.alal.downloader.core.engine.DownloadStore
import com.alal.downloader.core.engine.Segment
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Maps Room snapshots to engine models without exposing Room to the engine. */
@Singleton
class DownloadRepository @Inject constructor(private val database: DownloadDatabase) : DownloadStore {
    override suspend fun delete(id: String) = database.downloads().delete(id)

    override suspend fun load(): List<DownloadState> = withContext(Dispatchers.IO) {
        database.downloads().load().map { row ->
            val entity = row.download
            val json = JSONObject(entity.headersJson)
            val headers = json.keys().asSequence().associateWith { json.getString(it) }
            DownloadState(
                id = entity.id,
                request = DownloadRequest(entity.url, entity.requestFileName, headers, entity.referrerPageUrl,
                    File(entity.targetDir), entity.destinationKind, entity.treeUri, entity.segmentCount, entity.preserveFileName,
                    cookies = entity.cookies ?: headers.entries.find { it.key.equals("Cookie", true) }?.value,
                    referer = entity.referer ?: headers.entries.find { it.key.equals("Referer", true) }?.value ?: entity.referrerPageUrl,
                    userAgent = entity.userAgent ?: headers.entries.find { it.key.equals("User-Agent", true) }?.value),
                destinationUri = entity.destinationUri,
                fileName = entity.fileName,
                totalBytes = entity.totalBytes,
                segments = row.segments.sortedBy { it.segmentIndex }.map { Segment(it.segmentIndex, it.start, it.end, it.downloaded) },
                status = DownloadStatus.valueOf(entity.status),
                eTag = entity.eTag, lastModified = entity.lastModified, finalUrl = entity.finalUrl,
                acceptsRanges = entity.acceptsRanges,
                error = when (entity.errorType) {
                    "Network" -> DownloadError.Network(entity.errorMessage ?: "Network failure")
                    "Forbidden" -> DownloadError.Forbidden()
                    "NotFound" -> DownloadError.NotFound()
                    "RangeNotSatisfiable" -> DownloadError.RangeNotSatisfiable()
                    "LinkExpired" -> DownloadError.LinkExpired()
                    "DiskFull" -> DownloadError.DiskFull()
                    null -> null
                    else -> DownloadError.Unknown(entity.errorMessage ?: "Unknown failure")
                },
            )
        }
    }

    override suspend fun save(state: DownloadState) = withContext(Dispatchers.IO) {
        val entity = DownloadEntity(
            id = state.id,
            url = state.request.url,
            requestFileName = state.request.fileName,
            headersJson = JSONObject(state.request.headers).toString(),
            referrerPageUrl = state.request.referrerPageUrl,
            targetDir = state.request.targetDir.absolutePath,
            fileName = state.fileName,
            totalBytes = state.totalBytes,
            status = state.status.name,
            eTag = state.eTag,
            lastModified = state.lastModified,
            finalUrl = state.finalUrl,
            acceptsRanges = state.acceptsRanges,
            errorType = state.error?.javaClass?.simpleName,
            errorMessage = state.error?.message,
            destinationKind = state.request.destinationKind,
            treeUri = state.request.treeUri,
            destinationUri = state.destinationUri,
            segmentCount = state.request.segmentCount,
            preserveFileName = state.request.preserveFileName,
            cookies = state.request.cookies, referer = state.request.referer, userAgent = state.request.userAgent,
        )
        database.downloads().save(entity, state.segments.map {
            SegmentEntity(state.id, it.index, it.start, it.end, it.downloaded)
        })
    }
}