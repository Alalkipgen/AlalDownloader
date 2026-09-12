package app.onedown.feature.downloads

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.onedown.core.engine.DownloadState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Grants read-only access to one completed destination, never a raw file URI. */
object DownloadFiles {
    suspend fun open(context: Context, state: DownloadState) = withContext(Dispatchers.IO) {
        val uri = state.destinationUri?.let(Uri::parse) ?: run {
            val file = File(File(state.request.targetDir, state.id), state.fileName)
            check(file.isFile) { "Downloaded file is missing" }
            val root = File(context.filesDir, state.id).canonicalFile
            check(file.canonicalFile.parentFile == root && root.parentFile == context.filesDir.canonicalFile) { "Invalid legacy destination" }
            val directory = File(context.cacheDir, "opened-downloads/${state.id}")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare file for opening" }
            val copy = File(directory, app.onedown.core.engine.FileNames.sanitize(state.fileName))
            try {
                file.inputStream().use { input -> copy.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                } }
            } catch (failure: Exception) { copy.delete(); throw failure }
            FileProvider.getUriForFile(context, context.packageName + ".downloads", copy)
        }
        context.contentResolver.openFileDescriptor(uri, "r")?.close() ?: error("Cannot read downloaded file")
        val mime = context.contentResolver.getType(uri)?.takeUnless { it == "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(state.fileName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("Download", uri) }
        withContext(Dispatchers.Main.immediate) {
            context.startActivity(Intent.createChooser(view, "Open download").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }
}