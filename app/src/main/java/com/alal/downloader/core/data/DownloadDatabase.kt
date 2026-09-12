package com.alal.downloader.core.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.Transaction

/** Persisted request, representation metadata and lifecycle state. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val url: String,
    val requestFileName: String,
    val headersJson: String,
    val referrerPageUrl: String?,
    val targetDir: String,
    val fileName: String,
    val totalBytes: Long,
    val status: String,
    val eTag: String?,
    val lastModified: String?,
    val finalUrl: String,
    val acceptsRanges: Boolean,
    val errorType: String?,
    val errorMessage: String?,
    @ColumnInfo(defaultValue = "'file'") val destinationKind: String = "file",
    val treeUri: String? = null,
    val destinationUri: String? = null,
    val segmentCount: Int? = null,
    @ColumnInfo(defaultValue = "0") val preserveFileName: Boolean = false,
)

/** Durable byte offsets belonging to a single parent download. */
@Entity(
    tableName = "segments",
    primaryKeys = ["downloadId", "segmentIndex"],
    foreignKeys = [ForeignKey(
        entity = DownloadEntity::class, parentColumns = ["id"], childColumns = ["downloadId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("downloadId")],
)
data class SegmentEntity(val downloadId: String, val segmentIndex: Int, val start: Long, val end: Long, val downloaded: Long)

/** Transactionally loaded parent and its segment checkpoints. */
data class DownloadWithSegments(
    @Embedded val download: DownloadEntity,
    @Relation(parentColumn = "id", entityColumn = "downloadId") val segments: List<SegmentEntity>,
)

/** Atomic persistence operations for complete transfer snapshots. */
@Dao
abstract class DownloadDao {
    @Query("DELETE FROM downloads WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Transaction
    @Query("SELECT * FROM downloads ORDER BY rowid")
    abstract suspend fun load(): List<DownloadWithSegments>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertDownload(download: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSegments(segments: List<SegmentEntity>)

    @Transaction
    open suspend fun save(download: DownloadEntity, segments: List<SegmentEntity>) {
        insertDownload(download)
        insertSegments(segments)
    }
}

/** Room database for restart-safe download metadata and segment offsets. */
@Database(entities = [DownloadEntity::class, SegmentEntity::class], version = 3, exportSchema = true)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
}