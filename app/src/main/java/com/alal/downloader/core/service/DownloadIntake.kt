package com.alal.downloader.core.service

import com.alal.downloader.core.engine.DownloadRequest
import com.alal.downloader.core.engine.FilenameResolver
import javax.inject.Inject
import javax.inject.Singleton

/** Common HTTP intake; queued tasks always probe and sniff before completion. */
@Singleton
class DownloadIntake internal constructor(private val enqueue: suspend (DownloadRequest, Boolean) -> String) {
    @Inject constructor(coordinator: DownloadCoordinator) : this({ request, front -> coordinator.add(request, front) })

    suspend fun add(request: DownloadRequest, front: Boolean = false): String {
        require(!request.url.trim().startsWith("magnet:", true)) { "Torrent not supported yet" }
        val url = FilenameResolver.normalizeUrl(request.url)
        val name = request.fileName.takeUnless { it.isBlank() || it == "download.bin" }
            ?: FilenameResolver.resolve(url, mime = request.mimeType)
        return enqueue(request.copy(url = url, fileName = name), front)
    }
}