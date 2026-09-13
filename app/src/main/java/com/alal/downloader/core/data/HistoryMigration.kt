package com.alal.downloader.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds history without rebuilding or changing any download tables. */
object HistoryMigration : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, url TEXT NOT NULL, title TEXT NOT NULL, host TEXT NOT NULL, faviconUrl TEXT, visitedAt INTEGER NOT NULL, visitCount INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_history_url ON history (url)")
    }
}