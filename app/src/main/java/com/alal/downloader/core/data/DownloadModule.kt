package com.alal.downloader.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.alal.downloader.core.engine.DownloadEngine
import com.alal.downloader.core.engine.TlsPolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

/** Application-scoped wiring for networking, storage and the transfer queue. */
@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): DownloadDatabase =
        Room.databaseBuilder(context, DownloadDatabase::class.java, "downloads.db")
            .addMigrations(object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN destinationKind TEXT NOT NULL DEFAULT 'file'")
                    db.execSQL("ALTER TABLE downloads ADD COLUMN treeUri TEXT")
                    db.execSQL("ALTER TABLE downloads ADD COLUMN destinationUri TEXT")
                }
            }, object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE downloads ADD COLUMN segmentCount INTEGER")
                    db.execSQL("ALTER TABLE downloads ADD COLUMN preserveFileName INTEGER NOT NULL DEFAULT 0")
                }
            }, HistoryMigration, BrowserHeadersMigration).build()

    @Provides
    @Singleton
    fun client(@ApplicationContext context: Context): OkHttpClient {
        // Hosts the user opted out of certificate validation survive restarts so queued transfers can resume.
        val preferences = context.getSharedPreferences("download_settings", Context.MODE_PRIVATE)
        TlsPolicy.attach(object : TlsPolicy.Store {
            override fun load(): Set<String> = preferences.getStringSet("insecure_hosts", emptySet()).orEmpty().toSet()
            override fun save(hosts: Set<String>) {
                preferences.edit().putStringSet("insecure_hosts", hosts.toSet()).apply()
            }
        })
        return OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                if (com.alal.downloader.BuildConfig.DEBUG) {
                    val request = chain.request()
                    android.util.Log.d("AlalHttp", "${request.method} host=${request.url.host} " +
                        "Cookie=${request.header("Cookie") != null} Referer=${request.header("Referer") != null}")
                }
                chain.proceed(chain.request())
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    @Provides
    @Singleton
    fun engine(client: OkHttpClient, repository: DownloadRepository, storage: AndroidDownloadStorage): DownloadEngine =
        DownloadEngine(client, repository, storage = storage)
}
