package com.alal.downloader.feature.browser

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.alal.downloader.core.data.DownloadDatabase
import com.alal.downloader.core.data.HistoryEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Application-scoped history persistence; incognito writes are gated synchronously. */
@Singleton
class HistoryRepository @Inject constructor(database: DownloadDatabase, @ApplicationContext context: Context) {
    private val dao = database.history()
    private val preferences = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val privateMode = MutableStateFlow(preferences.getBoolean("incognito", false))
    val incognito = privateMode.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    private var privacyGeneration = 0L

    @Synchronized
    fun setIncognito(value: Boolean) {
        privacyGeneration++
        privateMode.value = value
        preferences.edit().putBoolean("incognito", value).apply()
    }

    @Synchronized
    fun record(url: String?, title: String?, host: String?, favicon: Bitmap?) {
        if (incognito.value || url == null || !HistoryPolicy.recordable(url)) return
        val generation = privacyGeneration
        val visitedAt = System.currentTimeMillis()
        val icon = favicon?.takeUnless { it.isRecycled }?.let { Bitmap.createScaledBitmap(it, 36, 36, true).copy(Bitmap.Config.ARGB_8888, false) }
        scope.launch {
            try {
                val encoded = icon?.let { image ->
                    val bytes = ByteArrayOutputStream()
                    image.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                    "data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                }
                if (!incognito.value && generation == synchronized(this@HistoryRepository) { privacyGeneration }) {
                    dao.record(HistoryEntry(url = url, title = title?.takeIf { it.isNotBlank() } ?: url,
                        host = host ?: url.toHttpUrlOrNull()?.host.orEmpty(), faviconUrl = encoded, visitedAt = visitedAt))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error.value = "Could not save history" }
            finally { icon?.recycle() }
        }
    }

    private fun escaped(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    fun entries(query: String) = if (query.isBlank()) dao.recent(Int.MAX_VALUE) else dao.search(escaped(query))
    fun suggestions(prefix: String) = dao.suggestions(escaped(prefix), 6).map {
        HistoryPolicy.suggestions(it, HistoryEntry::visitCount, HistoryEntry::visitedAt)
    }
    suspend fun delete(entry: HistoryEntry) = dao.deleteById(entry.id)
    suspend fun restore(entry: HistoryEntry) { dao.insert(entry) }
    suspend fun clear(since: Long?) { if (since == null) dao.deleteAll() else dao.deleteSince(since) }
}