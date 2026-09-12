package app.onedown.core.engine

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Seekable destination with explicit durability and ownership. */
interface DownloadOutput : Closeable {
    fun write(position: Long, bytes: ByteArray, count: Int)
    fun resize(length: Long)
    fun sync()
}

/** Platform boundary for local files and persisted document destinations. */
interface DownloadStorage {
    fun prepare(state: DownloadState): DownloadState
    fun length(state: DownloadState): Long?
    fun open(state: DownloadState): DownloadOutput
    fun complete(state: DownloadState)
    fun delete(state: DownloadState)
}

/** Local-file backend retained for legacy downloads and JVM tests. */
class FileDownloadStorage : DownloadStorage {
    private fun file(state: DownloadState) = File(File(state.request.targetDir, state.id), state.fileName)

    override fun prepare(state: DownloadState): DownloadState {
        val directory = file(state).parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create download directory" }
        return state
    }

    override fun length(state: DownloadState): Long? = file(state).takeIf { it.isFile }?.length()
    override fun open(state: DownloadState): DownloadOutput = FileDownloadOutput(file(state))
    override fun complete(state: DownloadState) = Unit
    override fun delete(state: DownloadState) {
        val target = file(state)
        check(!target.exists() || target.delete()) { "Cannot delete downloaded file" }
        target.parentFile?.delete()
    }
}

/** RandomAccessFile adapter used by the engine's local-file backend. */
class FileDownloadOutput(file: File) : DownloadOutput {
    private val output = RandomAccessFile(file, "rw")
    override fun write(position: Long, bytes: ByteArray, count: Int) {
        output.seek(position)
        output.write(bytes, 0, count)
    }
    override fun resize(length: Long) = output.setLength(length)
    override fun sync() = output.fd.sync()
    override fun close() = output.close()
}

/** Drops only checkpoints whose persisted extent is absent from the destination. */
fun recoverSegments(segments: List<Segment>, length: Long?): List<Segment> = segments.map {
    if (length == null || it.downloaded < 0 ||
        (it.length >= 0 && it.downloaded > it.length) ||
        (it.downloaded > 0 && (it.start > length || it.downloaded > length - it.start))
    ) it.copy(downloaded = 0) else it
}