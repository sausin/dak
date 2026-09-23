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
 * Checks that the migration chain from every older version (1, 2, 3, 4) produces exactly the schema Room generates for
 * the current version 5 (the same comparison Room makes when it opens a migrated database). Older versions are
 * reconstructed from Room's current DDL minus what the migrations add, so the test needs no exported schema file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndexMigrationSchemaTest {

    /** What each migration adds: new tables, new indices, new columns (DDL fragments), replaced table DDL. */
    private class Delta(
        val tables: Set<String>,
        val indices: Set<String> = emptySet(),
        val columns: Map<String, List<String>> = emptyMap(),
        val replacedTables: Map<String, String> = emptyMap(),
    )

    private val delta12 = Delta(
        tables = setOf(Tables.SENDER_FOLD, Tables.CONVERSATION_ALIAS, Tables.ACCOUNT_ALIAS),
        indices = setOf("index_${Tables.MESSAGE}_repeatGroup"),
        columns = mapOf(
            Tables.MESSAGE to listOf(", `repeatGroup` TEXT"),
            Tables.ACCOUNT to listOf(", `maskedNumber` TEXT"),
        ),
    )

    private val delta23 = Delta(
        tables = setOf(Tables.ACCOUNT_TYPE_OVERRIDE),
        columns = mapOf(Tables.ACCOUNT to listOf(", `linkedAccountId` TEXT")),
        // Versions 1-2 keyed ledger entries by message alone and had no viaAccountId.
        replacedTables = mapOf(
            Tables.LEDGER_ENTRY to "CREATE TABLE IF NOT EXISTS `${Tables.LEDGER_ENTRY}` (`messageKey` TEXT NOT NULL, " +
                "`accountId` TEXT NOT NULL, `dateMillis` INTEGER NOT NULL, `direction` TEXT NOT NULL, " +
                "`originalMinor` INTEGER NOT NULL, `originalCurrency` TEXT NOT NULL, `indicativeMinor` INTEGER, " +
                "`indicativeCurrency` TEXT, `rate` TEXT, `rateDateMillis` INTEGER, `settled` INTEGER NOT NULL, " +
                "`markupPercent` TEXT, `balanceAfterMinor` INTEGER, `balanceAfterCurrency` TEXT, `merchant` TEXT, " +
                "`reference` TEXT, PRIMARY KEY(`messageKey`))",
        ),
    )

    private val delta34 = Delta(
        tables = emptySet(),
        columns = mapOf(
            Tables.MESSAGE to listOf(", `deliveryStatus` INTEGER NOT NULL DEFAULT -1", ", `deliveredAtMillis` INTEGER"),
        ),
    )

    private val delta45 = Delta(
        tables = setOf(Tables.AUTOMATION_RUN),
        indices = setOf("index_${Tables.AUTOMATION_RUN}_ruleId_atMillis", "index_${Tables.AUTOMATION_RUN}_atMillis"),
    )

    @Test
    fun migratedVersion1MatchesRoomVersion5() = assertMigrates(
        listOf(delta12, delta23, delta34, delta45),
        listOf(IndexMigrations.MIGRATION_1_2, IndexMigrations.MIGRATION_2_3, IndexMigrations.MIGRATION_3_4, IndexMigrations.MIGRATION_4_5),
    )

    @Test
    fun migratedVersion2MatchesRoomVersion5() = assertMigrates(
        listOf(delta23, delta34, delta45),
        listOf(IndexMigrations.MIGRATION_2_3, IndexMigrations.MIGRATION_3_4, IndexMigrations.MIGRATION_4_5),
    )

    @Test
    fun migratedVersion3MatchesRoomVersion5() = assertMigrates(
        listOf(delta34, delta45),
        listOf(IndexMigrations.MIGRATION_3_4, IndexMigrations.MIGRATION_4_5),
    )

    @Test
    fun migratedVersion4MatchesRoomVersion5() = assertMigrates(listOf(delta45), listOf(IndexMigrations.MIGRATION_4_5))

    private fun assertMigrates(deltas: List<Delta>, migrations: List<androidx.room.migration.Migration>) {
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

        val newTables = deltas.flatMap { it.tables }.toSet()
        val newIndices = deltas.flatMap { it.indices }.toSet()
        val ordered = schema.filter { it.type == "table" } + schema.filter { it.type == "index" } + schema.filter { it.type == "trigger" }
        for (row in ordered) {
            if (row.name in newTables || row.name in newIndices) continue
            var sql = row.sql
            for (delta in deltas) {
                delta.replacedTables[row.name]?.let { sql = it }
                for (column in delta.columns[row.name].orEmpty()) {
                    assertTrue(sql.contains(column), "Room DDL of ${row.name} no longer contains $column: $sql")
                    sql = sql.replace(column, "")
                }
            }
            old.execSQL(sql)
        }
        for (migration in migrations) migration.migrate(old)

        val tables = schema.filter { it.type == "table" && !it.sql.startsWith("CREATE VIRTUAL", ignoreCase = true) }.map { it.name }
        assertTrue(tables.containsAll(newTables + Tables.MESSAGE + Tables.ACCOUNT + Tables.LEDGER_ENTRY))
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
