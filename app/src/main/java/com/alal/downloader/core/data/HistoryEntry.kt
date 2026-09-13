package com.alal.downloader.core.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** One URL and its latest successful visit. */
@Entity(tableName = "history", indices = [Index(value = ["url"], unique = true)])
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val host: String,
    val faviconUrl: String? = null,
    val visitedAt: Long,
    val visitCount: Int = 1,
)

/** Observable history queries and atomic visit updates. */
@Dao
abstract class HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC, id DESC LIMIT :limit")
    abstract fun recent(limit: Int): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE title LIKE '%' || :query || '%' ESCAPE '\' OR url LIKE '%' || :query || '%' ESCAPE '\' OR host LIKE '%' || :query || '%' ESCAPE '\' ORDER BY visitedAt DESC, id DESC")
    abstract fun search(query: String): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE title LIKE :prefix || '%' ESCAPE '\' OR host LIKE :prefix || '%' ESCAPE '\' OR url LIKE :prefix || '%' ESCAPE '\' OR url LIKE 'https://' || :prefix || '%' ESCAPE '\' OR url LIKE 'http://' || :prefix || '%' ESCAPE '\' OR host LIKE 'www.' || :prefix || '%' ESCAPE '\' ORDER BY visitCount DESC, visitedAt DESC, id DESC LIMIT :limit")
    abstract fun suggestions(prefix: String, limit: Int = 6): Flow<List<HistoryEntry>>

    @Query("DELETE FROM history WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("DELETE FROM history WHERE visitedAt < :ts")
    abstract suspend fun deleteOlderThan(ts: Long)

    @Query("DELETE FROM history WHERE visitedAt >= :ts")
    abstract suspend fun deleteSince(ts: Long)

    @Query("DELETE FROM history")
    abstract suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(entry: HistoryEntry): Long

    @Query("UPDATE history SET title = :title, host = :host, faviconUrl = COALESCE(:favicon, faviconUrl), visitedAt = :time, visitCount = visitCount + 1 WHERE url = :url")
    abstract suspend fun refresh(url: String, title: String, host: String, favicon: String?, time: Long)

    @Transaction
    open suspend fun record(entry: HistoryEntry) {
        if (insert(entry) == -1L) refresh(entry.url, entry.title, entry.host, entry.faviconUrl, entry.visitedAt)
    }
}