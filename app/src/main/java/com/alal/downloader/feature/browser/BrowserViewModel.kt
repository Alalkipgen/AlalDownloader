package com.alal.downloader.feature.browser

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alal.downloader.core.data.DownloadSettings
import com.alal.downloader.core.engine.DownloadRequest
import com.alal.downloader.core.service.DownloadCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Submits confirmed browser captures through foreground service protection. */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val coordinator: DownloadCoordinator,
    private val intake: com.alal.downloader.core.service.DownloadIntake,
    private val settings: DownloadSettings,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val treeUri = settings.treeUri
    val defaultSegments = settings.segments
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val mutableSubmitting = MutableStateFlow(false)
    val submitting = mutableSubmitting.asStateFlow()

    fun request(capture: BrowserCapture, name: String, segments: Int): DownloadRequest {
        val tree = settings.treeUri.value
        check(tree != null || Build.VERSION.SDK_INT >= 29) { "Choose a folder first" }
        require(segments in 1..32) { "Segment count must be 1..32" }
        return DownloadRequest(capture.url, BrowserPolicy.sanitize(name), capture.headers, capture.referrer,
            context.filesDir, if (tree == null) "media" else "tree", tree, segments, preserveFileName = true,
            mimeType = capture.mimeType, contentLength = capture.size)
    }

    fun submit(capture: BrowserCapture, name: String, segments: Int, refreshed: () -> Unit, done: () -> Unit) {
        if (mutableSubmitting.value) return
        mutableSubmitting.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (capture.refreshId != null) coordinator.replaceLink(capture.refreshId, capture.url, capture.headers)
                    else intake.add(request(capture, name, segments), front = true)
                }
                mutableMessage.value = if (capture.refreshId == null) "Download submitted at front of queue" else "Link replaced; resuming with validator checks"
                if (capture.refreshId != null) refreshed()
                done()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableMessage.value = failure.message ?: "Cannot start download"
            } finally {
                mutableSubmitting.value = false
            }
        }
    }

    fun clearMessage() { mutableMessage.value = null }
}