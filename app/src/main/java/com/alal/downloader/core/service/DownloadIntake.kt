package com.alal.downloader.core.service

import com.alal.downloader.core.engine.DownloadRequest
import com.alal.downloader.core.engine.FilenameResolver
import javax.inject.Inject
import javax.inject.Singleton

/** Common HTTP intake; queued tasks always probe and sniff before completion. */
@Singleton
class DownloadIntake @Inject constructor(private val coordinator: DownloadCoordinator) {
    suspend fun add(request: DownloadRequest, front: Boolean = false): String {
        val url = FilenameResolver.normalizeUrl(request.url)
        val name = request.fileName.takeUnless { it.isBlank() || it == "download.bin" }
            ?: FilenameResolver.resolve(url, mime = request.mimeType)
        return coordinator.add(request.copy(url = url, fileName = name), front)
    }
}