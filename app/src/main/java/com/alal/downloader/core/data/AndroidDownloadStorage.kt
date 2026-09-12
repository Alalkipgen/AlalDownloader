package com.alal.downloader.core.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.alal.downloader.core.engine.DownloadOutput
import com.alal.downloader.core.engine.DownloadState
import com.alal.downloader.core.engine.DownloadStorage
import com.alal.downloader.core.engine.FileDownloadStorage
import com.alal.downloader.core.engine.FileNames
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/** Seekable SAF and MediaStore destinations, with legacy local-file support. */
@Singleton
class AndroidDownloadStorage @Inject constructor(@ApplicationContext context: Context) : DownloadStorage {
    private val resolver = context.contentResolver
    private val legacy = FileDownloadStorage()

    override fun prepare(state: DownloadState): DownloadState = synchronized(allocationLock) {
        if (state.request.destinationKind == "file") return legacy.prepare(state)
        if (state.destinationUri != null && length(state) != null) return state
        val requestedName = FileNames.sanitize(state.fileName)
        val existing = existingNames(state)
        var number = 0
        var name = requestedName
        while (name in existing) { number++; name = FileNames.numbered(requestedName, number) }
        val uri = when (state.request.destinationKind) {
            "tree" -> {
                val tree = Uri.parse(requireNotNull(state.request.treeUri) { "Select a download folder" })
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
            }
            "media" -> {
                if (Build.VERSION.SDK_INT < 29) error("Choose a folder on Android 7–9")
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Alal")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })
            }
            else -> error("Unknown destination type")
        } ?: error("Cannot create destination")
        val actualName = resolver.query(uri, arrayOf("_display_name"), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: name
        return state.copy(fileName = actualName, destinationUri = uri.toString(), segments = state.segments.map { it.copy(downloaded = 0) })
    }

    private fun existingNames(state: DownloadState): Set<String> {
        val tree = state.request.treeUri?.let(Uri::parse)
        val uri = if (tree != null) DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            else if (Build.VERSION.SDK_INT >= 29) MediaStore.Downloads.EXTERNAL_CONTENT_URI else error("Select a folder")
        val selection = if (tree == null) "${MediaStore.MediaColumns.RELATIVE_PATH} = ?" else null
        val args = if (tree == null) arrayOf(Environment.DIRECTORY_DOWNLOADS + "/Alal/") else null
        return resolver.query(uri, arrayOf("_display_name"), selection, args, null)?.use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        } ?: error("Cannot inspect destination for filename collisions")
    }

    override fun length(state: DownloadState): Long? {
        if (state.request.destinationKind == "file") return legacy.length(state)
        val uri = state.destinationUri?.let(Uri::parse) ?: return null
        return try {
            val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open destination")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.channel.size() }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    override fun open(state: DownloadState): DownloadOutput {
        if (state.request.destinationKind == "file") return legacy.open(state)
        val descriptor = resolver.openFileDescriptor(Uri.parse(requireNotNull(state.destinationUri)), "rw")
            ?: throw IOException("Cannot open destination for writing")
        return DocumentOutput(descriptor)
    }

    override fun complete(state: DownloadState) {
        if (state.destinationUri != null && state.request.destinationKind == "media" && Build.VERSION.SDK_INT >= 29) {
            check(resolver.update(Uri.parse(requireNotNull(state.destinationUri)), ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null) == 1) { "Cannot publish completed download" }
        }
    }

    override fun delete(state: DownloadState) {
        if (state.request.destinationKind == "file") { legacy.delete(state); return }
        val uri = state.destinationUri?.let(Uri::parse) ?: return
        resolver.query(uri, arrayOf("_display_name"), null, null, null)?.use {
            if (!it.moveToFirst()) return
        } ?: throw IOException("Cannot verify destination; download entry retained")
        val deleted = if (state.request.destinationKind == "tree") DocumentsContract.deleteDocument(resolver, uri)
            else resolver.delete(uri, null, null) > 0
        check(deleted) { "Provider refused to delete the file; download entry retained" }
    }

    companion object {
        private val allocationLock = Any()
    }
}

private class DocumentOutput(descriptor: ParcelFileDescriptor) : DownloadOutput {
    private val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    private val channel = stream.channel

    init {
        try {
            channel.position(channel.position())
            channel.size()
        } catch (failure: Exception) {
            stream.close()
            throw IOException("Folder provider does not support random access; choose local storage", failure)
        }
    }

    override fun write(position: Long, bytes: ByteArray, count: Int) {
        val buffer = ByteBuffer.wrap(bytes, 0, count)
        var offset = position
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer, offset)
            if (written <= 0) throw IOException("Destination write made no progress")
            offset += written
        }
    }

    override fun resize(length: Long) {
        require(length >= 0)
        channel.truncate(length)
        if (channel.size() < length) write(length - 1, byteArrayOf(0), 1)
    }

    override fun sync() = channel.force(true)
    override fun close() = stream.close()
}