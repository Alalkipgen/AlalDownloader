package com.alal.downloader.core.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BrowserHeadersMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        DownloadDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun migrate4To5PreservesTransfersAndReplaysBrowserContext() {
        val name = "browser-headers-migration"
        helper.createDatabase(name, 4).apply {
            execSQL("""INSERT INTO downloads (id,url,requestFileName,headersJson,referrerPageUrl,targetDir,fileName,totalBytes,status,finalUrl,acceptsRanges)
                VALUES ('kept','https://example.com/file','file.zip','{"cookie":"session=secret","referer":"https://example.com/page","user-agent":"Browser UA"}','https://example.com/source','/downloads','file.zip',1000,'PAUSED','https://example.com/file',1)""")
            execSQL("INSERT INTO segments VALUES ('kept',0,0,999,123)")
            execSQL("INSERT INTO history (url,title,host,visitedAt,visitCount) VALUES ('https://example.com','Example','example.com',1,1)")
            close()
        }
        helper.runMigrationsAndValidate(name, 5, true, BrowserHeadersMigration).apply {
            query("SELECT cookies,referer,userAgent,totalBytes,status FROM downloads WHERE id='kept'").use {
                assertTrue(it.moveToFirst())
                assertEquals("session=secret", it.getString(0)); assertEquals("https://example.com/page", it.getString(1))
                assertEquals("Browser UA", it.getString(2)); assertEquals(1000L, it.getLong(3)); assertEquals("PAUSED", it.getString(4))
            }
            query("SELECT downloaded FROM segments WHERE downloadId='kept'").use {
                assertTrue(it.moveToFirst()); assertEquals(123L, it.getLong(0))
            }
            query("SELECT COUNT(*) FROM history").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            close()
        }
        val database = Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            DownloadDatabase::class.java, name).addMigrations(BrowserHeadersMigration).build()
        try {
            runBlocking {
                val repository = DownloadRepository(database)
                val state = repository.load().single()
                assertEquals("session=secret", state.request.cookies)
                assertEquals("https://example.com/page", state.request.referer)
                assertEquals("Browser UA", state.request.userAgent)
                repository.save(state.copy(request = state.request.copy(headers = emptyMap())))
                val restored = repository.load().single()
                assertEquals(state.request.cookies, restored.request.cookies)
                assertEquals(state.request.referer, restored.request.referer)
                assertEquals(state.request.userAgent, restored.request.userAgent)
                assertEquals(state.segments, restored.segments)
            }
        } finally { database.close() }
    }
}