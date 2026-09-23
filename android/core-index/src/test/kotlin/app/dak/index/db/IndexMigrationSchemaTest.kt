package app.dak.index.db

import android.content.Context
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.dak.index.sql.Tables
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks that [IndexMigrations.MIGRATION_1_2] produces exactly the schema Room generates for version 2 (the same
 * comparison Room makes when it opens a migrated database). Version 1 is reconstructed from Room's version-2 DDL
 * minus what the migration adds, so the test needs no exported schema file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndexMigrationSchemaTest {

    private val newTables = setOf(Tables.SENDER_FOLD, Tables.CONVERSATION_ALIAS, Tables.ACCOUNT_ALIAS)
    private val newIndices = setOf("index_${Tables.MESSAGE}_repeatGroup")
    private val newColumns = mapOf(
        Tables.MESSAGE to ", `repeatGroup` TEXT",
        Tables.ACCOUNT to ", `maskedNumber` TEXT",
    )

    @Test
    fun migratedVersion1MatchesRoomVersion2() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.inMemoryDatabaseBuilder(context, DakIndexDatabase::class.java).allowMainThreadQueries().build()
        val fresh = room.openHelper.writableDatabase
        val schema = masterRows(fresh)

        val old = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        ).writableDatabase

        val ordered = schema.filter { it.type == "table" } + schema.filter { it.type == "index" } + schema.filter { it.type == "trigger" }
        for (row in ordered) {
            if (row.name in newTables || row.name in newIndices) continue
            var sql = row.sql
            newColumns[row.name]?.let { column ->
                assertTrue(sql.contains(column), "Room DDL of ${row.name} no longer contains $column: $sql")
                sql = sql.replace(column, "")
            }
            old.execSQL(sql)
        }
        IndexMigrations.MIGRATION_1_2.migrate(old)

        val tables = schema.filter { it.type == "table" && !it.sql.startsWith("CREATE VIRTUAL", ignoreCase = true) }.map { it.name }
        assertTrue(tables.containsAll(newTables + Tables.MESSAGE + Tables.ACCOUNT))
        for (table in tables) {
            assertEquals(TableInfo.read(fresh, table), TableInfo.read(old, table), "table $table")
        }
        room.close()
        old.close()
    }

    private data class MasterRow(val type: String, val name: String, val sql: String)

    private fun masterRows(db: SupportSQLiteDatabase): List<MasterRow> {
        val out = ArrayList<MasterRow>()
        db.query("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                if (name.startsWith("sqlite_") || name == "android_metadata" || name == "room_master_table") continue
                if (name.startsWith(Tables.MESSAGE_FTS + "_")) continue // FTS shadow tables, created by the virtual table
                out += MasterRow(c.getString(0), name, c.getString(2))
            }
        }
        return out
    }
}
