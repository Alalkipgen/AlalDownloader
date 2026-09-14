package com.alal.downloader.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

/** Adds explicit browser request context without discarding existing transfers. */
object BrowserHeadersMigration : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN cookies TEXT")
        db.execSQL("ALTER TABLE downloads ADD COLUMN referer TEXT")
        db.execSQL("ALTER TABLE downloads ADD COLUMN userAgent TEXT")
        db.query("SELECT id,headersJson,referrerPageUrl FROM downloads").use { rows ->
            while (rows.moveToNext()) {
                val json = JSONObject(rows.getString(1))
                fun header(name: String): String? = json.keys().asSequence()
                    .firstOrNull { it.equals(name, true) }?.let { json.getString(it) }
                db.execSQL("UPDATE downloads SET cookies=?,referer=?,userAgent=? WHERE id=?", arrayOf(
                    header("Cookie"), header("Referer") ?: rows.getString(2), header("User-Agent"), rows.getString(0),
                ))
            }
        }
    }
}
