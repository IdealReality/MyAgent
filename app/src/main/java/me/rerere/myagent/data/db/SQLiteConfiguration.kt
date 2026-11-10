package me.rerere.myagent.data.db

import android.content.Context
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import java.io.File

/** Use the same SQLite extensions for the live database and backup validation. */
internal object SQLiteConfiguration {
    const val DATABASE_NAME = "my_agent"

    /**
     * Builds before the rebrand stored chat history in `rikka_hub`. Rename that file once so
     * the rebrand does not strand existing conversations. Idempotent: it does nothing when the
     * new database already exists or when there is no legacy file to move.
     */
    private fun migrateLegacyDatabase(context: Context) {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        val dir = dbFile.parentFile ?: return
        if (dbFile.exists()) return
        val legacy = File(dir, LEGACY_DATABASE_NAME)
        if (!legacy.exists()) return
        if (!legacy.renameTo(dbFile)) return
        for (suffix in listOf("-wal", "-shm")) {
            val from = File(dir, LEGACY_DATABASE_NAME + suffix)
            if (from.exists()) {
                from.renameTo(File(dir, DATABASE_NAME + suffix))
            }
        }
    }

    fun configure(context: Context, configuration: SQLiteDatabaseConfiguration): SQLiteDatabaseConfiguration {
        configuration.customExtensions.add(
            SQLiteCustomExtension(context.applicationInfo.nativeLibraryDir + "/libsimple", null)
        )
        return configuration
    }

    fun openHelperFactory(context: Context) = RequerySQLiteOpenHelperFactory(
        listOf(RequerySQLiteOpenHelperFactory.ConfigurationOptions { configure(context, it) })
    ).also { migrateLegacyDatabase(context) }
}

private const val LEGACY_DATABASE_NAME = "rikka_hub"
