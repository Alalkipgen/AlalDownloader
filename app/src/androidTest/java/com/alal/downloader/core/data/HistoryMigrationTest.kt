package com.alal.downloader.core.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HistoryMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), DownloadDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migrate3To4PreservesDownloadsAndSegmentsAndSupportsHistory() {
        val name = "history-migration"
        helper.createDatabase(name, 3).apply {
            execSQL("""INSERT INTO downloads (id,url,requestFileName,headersJson,targetDir,fileName,totalBytes,status,finalUrl,acceptsRanges,destinationKind,preserveFileName,segmentCount)
                VALUES ('kept','https://example.com/file','file','{}','/downloads','file',1000,'PAUSED','https://example.com/file',1,'file',1,2)""")
            execSQL("INSERT INTO segments VALUES ('kept',0,0,499,123)")
            execSQL("INSERT INTO segments VALUES ('kept',1,500,999,45)")
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true, HistoryMigration).apply {
            query("SELECT totalBytes,segmentCount,preserveFileName,status FROM downloads WHERE id='kept'").use {
                assertTrue(it.moveToFirst())
                assertEquals(1000L, it.getLong(0)); assertEquals(2, it.getInt(1))
                assertEquals(1, it.getInt(2)); assertEquals("PAUSED", it.getString(3))
            }
            query("SELECT SUM(downloaded),COUNT(*) FROM segments WHERE downloadId='kept'").use {
                assertTrue(it.moveToFirst()); assertEquals(168L, it.getLong(0)); assertEquals(2, it.getInt(1))
            }
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, DownloadDatabase::class.java, name)
            .addMigrations(HistoryMigration).build()
        try {
                runBlocking {
                    val entry = HistoryEntry(url = "https://example.com", title = "First", host = "example.com", visitedAt = 1)
                    database.history().record(entry)
                    database.history().record(entry.copy(title = "Latest", visitedAt = 2))
                    assertEquals(2, database.downloads().load().single().segments.size)
                }
                database.openHelper.readableDatabase.query("SELECT title,visitCount,visitedAt FROM history").use {
                    assertTrue(it.moveToFirst()); assertEquals("Latest", it.getString(0))
                    assertEquals(2, it.getInt(1)); assertEquals(2L, it.getLong(2)); assertFalse(it.moveToNext())
                }
        } finally {
            database.close()
        }
    }
}
