package com.alal.downloader.feature.downloads

import android.content.Context
import android.os.Build
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alal.downloader.core.engine.DownloadEngine
import com.alal.downloader.core.engine.DownloadRequest
import com.alal.downloader.core.data.DownloadSettings
import com.alal.downloader.core.service.DownloadCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import com.alal.downloader.core.engine.DownloadStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Downloads commands and persistent app settings, independent of screen lifetime. */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val engine: DownloadEngine,
    private val coordinator: DownloadCoordinator,
    private val intake: com.alal.downloader.core.service.DownloadIntake,
    client: okhttp3.OkHttpClient,
    private val settings: DownloadSettings,
    val backgroundAccess: com.alal.downloader.core.service.BackgroundAccess,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val prober = DownloadProber { com.alal.downloader.core.engine.RangeProbe(client).probe(it) }

    suspend fun addFile(url: String, referrer: String, name: String, extension: String, wifi: Boolean, retry: Boolean, agent: String) = withContext(Dispatchers.IO) {
        val normalized = com.alal.downloader.core.engine.FilenameResolver.normalizeUrl(url)
        val tree = settings.treeUri.value
        check(tree != null || Build.VERSION.SDK_INT >= 29) { "Choose a download folder first" }
        val filename = if (name.isBlank()) com.alal.downloader.core.engine.FilenameResolver.resolve(normalized)
            else com.alal.downloader.core.engine.FileNames.sanitize(name.trim() + extension.trim().trimStart('.').takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty())
        intake.add(DownloadRequest(normalized, filename, targetDir = context.filesDir,
            destinationKind = if (tree == null) "media" else "tree", treeUri = tree,
            segmentCount = settings.segments.value, preserveFileName = name.isNotBlank(),
            referer = referrer.trim().takeIf { it.isNotEmpty() }, userAgent = agent,
            wifiOnly = wifi, retryOnFailure = retry))
    }
    val autoResume = settings.autoResume
    val interruptedWarning = coordinator.interruptedWarning
    fun dismissInterruptedWarning() = coordinator.dismissInterruptedWarning()
    fun shouldShowBackgroundPrompt(): Boolean = !settings.backgroundPromptShown && !backgroundAccess.ignored()
    fun markBackgroundPromptShown() { settings.backgroundPromptShown = true }
    fun setAutoResume(value: Boolean) = execute { settings.setAutoResume(value) }
    fun recover() = execute { coordinator.recover() }
    val downloads = engine.states
    val wifiOnly = settings.wifiOnly
    val treeUri = settings.treeUri
    val segments = settings.segments
    val concurrent = settings.concurrent
    val speed = settings.speed
    val theme = settings.theme
    private val mutableImport = MutableStateFlow("")
    val importedText = mutableImport.asStateFlow()
    private val mutableBatch = MutableStateFlow(false)
    val batchBusy = mutableBatch.asStateFlow()
    private val mutableBatchIds = MutableStateFlow<Set<String>>(emptySet())
    val batchIds = mutableBatchIds.asStateFlow()
    private val batchMessages = Channel<String>(Channel.UNLIMITED)
    val batchResults = batchMessages.receiveAsFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    init { execute { coordinator.recover(); coordinator.updateTransferSettings() } }

    fun add(url: String) = execute { addOne(url) }

    private suspend fun addOne(url: String): String =
        addFile(url, "", "", "", settings.wifiOnly.value, true, "")

    fun addBatch(input: String, done: () -> Unit) {
        if (mutableBatch.value) return
        mutableBatch.value = true
        viewModelScope.launch {
            var count = 0
            var processed = 0
            val ids = mutableSetOf<String>()
            var unsupported = 0
            try {
                withContext(Dispatchers.IO) {
                    val (urls, invalid) = DownloadPresentation.batch(input)
                    require(urls.isNotEmpty() && invalid.isEmpty()) { "Fix invalid lines before importing" }
                    for (url in urls) {
                        if (url.startsWith("magnet:", true)) { unsupported++; processed++; continue }
                        ids += addOne(url)
                        mutableBatchIds.value = mutableBatchIds.value + ids
                        count++
                        processed++
                    }
                }
                done()
                viewModelScope.launch {
                    val classified = downloads.first { states ->
                        states.filter { it.id in ids }.all {
                            it.status in setOf(DownloadStatus.NEEDS_BROWSER, DownloadStatus.COMPLETED,
                                DownloadStatus.FAILED, DownloadStatus.CANCELLED)
                        }
                    }
                    val pages = classified.count { it.id in ids && it.status == DownloadStatus.NEEDS_BROWSER }
                    batchMessages.send("$count added · $pages are web pages" +
                        if (unsupported > 0) " · $unsupported skipped: Torrent not supported yet" else "")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableError.value = "Added $count before import stopped: ${failure.message}. Already-added URLs will not be resubmitted."
                mutableImport.value = input.removePrefix("\uFEFF").lineSequence().filter { it.isNotBlank() }.drop(processed).joinToString("\n")
            }
            finally { mutableBatch.value = false }
        }
    }

    fun importText(uri: Uri) = execute {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(buffer)
                if (read < 0) break
                require(output.size() + read <= DownloadPresentation.MAX_INPUT) { "Text file exceeds 1 MiB" }
                output.write(buffer, 0, read)
            }
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
        } ?: error("Cannot open text file")
        mutableImport.value = text
    }

    fun clearImport() { mutableImport.value = "" }
    fun clearError() { mutableError.value = null }
    fun delete(id: String, deleteFile: Boolean) = execute { coordinator.delete(id, deleteFile) }
    fun setTransfer(segments: Int, concurrent: Int, speed: Long) = execute {
        settings.setTransfer(segments, concurrent, speed)
        coordinator.updateTransferSettings()
    }
    fun setTheme(value: String) = execute { settings.setTheme(value) }

    fun pause(id: String) = execute { coordinator.pause(id) }
    fun resume(id: String) = execute { coordinator.resume(id) }
    fun cancel(id: String) = execute { coordinator.cancel(id) }
    fun setWifiOnly(value: Boolean) = execute { settings.setWifiOnly(value); coordinator.updateNetwork() }
    fun setTree(uri: Uri?) = execute { settings.setTree(uri) }

    private fun execute(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                mutableError.value = null
                withContext(Dispatchers.IO) { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableError.value = failure.message ?: "Operation failed"
            }
        }
    }
}