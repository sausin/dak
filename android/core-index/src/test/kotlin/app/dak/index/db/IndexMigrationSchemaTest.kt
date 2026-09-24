package app.dak.index.db

import android.content.Context
import android.database.Cursor
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
 * Checks that the migration chain from every older version (1, 2, 3, 4, 5) produces exactly the schema Room generates
 * for the current version 6 (the same comparison Room makes when it opens a migrated database). Older versions are
 * reconstructed from Room's current DDL minus what the migrations add (newest first), so the test needs no exported
 * schema file.
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

    private val delta56 = Delta(
        tables = emptySet(),
        columns = mapOf(
            Tables.LEDGER_ENTRY to listOf(
                ", `transfer` INTEGER NOT NULL DEFAULT 0",
                ", `investmentAction` TEXT",
                ", `units` TEXT",
                ", `unitPrice` TEXT",
            ),
            Tables.ACCOUNT to listOf(", `unitsHeld` TEXT"),
        ),
    )

    @Test
    fun migratedVersion1MatchesRoomVersion6() = assertMigrates(
        listOf(delta12, delta23, delta34, delta45, delta56),
        listOf(
            IndexMigrations.MIGRATION_1_2, IndexMigrations.MIGRATION_2_3, IndexMigrations.MIGRATION_3_4, IndexMigrations.MIGRATION_4_5,
            IndexMigrations.MIGRATION_5_6,
        ),
    )

    @Test
    fun migratedVersion2MatchesRoomVersion6() = assertMigrates(
        listOf(delta23, delta34, delta45, delta56),
        listOf(IndexMigrations.MIGRATION_2_3, IndexMigrations.MIGRATION_3_4, IndexMigrations.MIGRATION_4_5, IndexMigrations.MIGRATION_5_6),
    )

    @Test
    fun migratedVersion3MatchesRoomVersion6() = assertMigrates(
        listOf(delta34, delta45, delta56),
        listOf(IndexMigrations.MIGRATION_3_4, IndexMigrations.MIGRATION_4_5, IndexMigrations.MIGRATION_5_6),
    )

    @Test
    fun migratedVersion4MatchesRoomVersion6() = assertMigrates(
        listOf(delta45, delta56),
        listOf(IndexMigrations.MIGRATION_4_5, IndexMigrations.MIGRATION_5_6),
    )

    @Test
    fun migratedVersion5MatchesRoomVersion6() = assertMigrates(listOf(delta56), listOf(IndexMigrations.MIGRATION_5_6))

    private fun assertMigrates(deltas: List<Delta>, migrations: List<androidx.room.migration.Migration>) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.inMemoryDatabaseBuilder(context, DakIndexDatabase::class.java).allowMainThreadQueries().build()
        val fresh = room.openHelper.writableDatabase
        val schema = masterRows(fresh)

        val old = openOld(context, name = null, version = 1)
        createOldSchema(old, schema, deltas)
        val newTables = deltas.flatMap { it.tables }.toSet()
        for (migration in migrations) migration.migrate(old)

        val tables = schema.filter { it.type == "table" && !it.sql.startsWith("CREATE VIRTUAL", ignoreCase = true) }.map { it.name }
        assertTrue(tables.containsAll(newTables + Tables.MESSAGE + Tables.ACCOUNT + Tables.LEDGER_ENTRY))
        for (table in tables) {
            // The SupportSQLiteDatabase overload is the one these handles support; the SQLiteConnection one is newer.
            @Suppress("DEPRECATION")
            assertEquals(TableInfo.read(fresh, table), TableInfo.read(old, table), "table $table")
        }
        room.close()
        old.close()
    }

    /** An empty database at [version] (file-backed when [name] is set, so Room can open it afterwards). */
    private fun openOld(context: Context, name: String?, version: Int): SupportSQLiteDatabase =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        ).writableDatabase

    /** Recreates the schema of the version before [deltas] from Room's current DDL (see the class comment). */
    private fun createOldSchema(old: SupportSQLiteDatabase, schema: List<MasterRow>, deltas: List<Delta>) {
        val newTables = deltas.flatMap { it.tables }.toSet()
        val newIndices = deltas.flatMap { it.indices }.toSet()
        val ordered = schema.filter { it.type == "table" } + schema.filter { it.type == "index" } + schema.filter { it.type == "trigger" }
        for (row in ordered) {
            if (row.name in newTables || row.name in newIndices) continue
            var sql = row.sql
            // Newest first: a table a migration replaced wholesale (2 -> 3) is swapped for its old DDL only after the
            // columns later migrations added to the current DDL are taken out.
            for (delta in deltas.asReversed()) {
                delta.replacedTables[row.name]?.let { sql = it }
                for (column in delta.columns[row.name].orEmpty()) {
                    assertTrue(sql.contains(column), "Room DDL of ${row.name} no longer contains $column: $sql")
                    sql = sql.replace(column, "")
                }
            }
            old.execSQL(sql)
        }
    }

    // --- Data preservation -----------------------------------------------------------------------------------

    @Test
    fun rowsWrittenAtVersion1SurviveTheUpgradeToVersion6() = assertPreservesData(1, listOf(delta12, delta23, delta34, delta45, delta56))

    @Test
    fun rowsWrittenAtVersion2SurviveTheUpgradeToVersion6() = assertPreservesData(2, listOf(delta23, delta34, delta45, delta56))

    @Test
    fun rowsWrittenAtVersion3SurviveTheUpgradeToVersion6() = assertPreservesData(3, listOf(delta34, delta45, delta56))

    @Test
    fun rowsWrittenAtVersion4SurviveTheUpgradeToVersion6() = assertPreservesData(4, listOf(delta45, delta56))

    @Test
    fun rowsWrittenAtVersion5SurviveTheUpgradeToVersion6() = assertPreservesData(5, listOf(delta56))

    /**
     * Fills every table of an old-version database with two rows of distinct values, lets Room open it through the
     * production migration list (so Room's own schema validation runs too), and checks that every value is still
     * there, that columns added since read their declared default, and that the full-text index still finds the
     * old rows. The only data a migration may drop is a table it replaced wholesale ([Delta.replacedTables]:
     * derived ledger entries, rebuilt by the re-index that ships with it).
     */
    private fun assertPreservesData(fromVersion: Int, deltas: List<Delta>) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val schema = Room.inMemoryDatabaseBuilder(context, DakIndexDatabase::class.java).allowMainThreadQueries().build().let { room ->
            masterRows(room.openHelper.writableDatabase).also { room.close() }
        }
        val name = "migration-data-v$fromVersion.db"
        context.deleteDatabase(name)

        val old = openOld(context, name, fromVersion)
        createOldSchema(old, schema, deltas)
        val virtual = schema.filter { it.sql.startsWith("CREATE VIRTUAL", ignoreCase = true) }.map { it.name }.toSet()
        val oldTables = tableNames(old).filterNot { it in virtual || it.startsWith(Tables.MESSAGE_FTS + "_") }
        assertTrue(Tables.MESSAGE in oldTables && Tables.PREFS in oldTables && Tables.AUTOMATION_RULE in oldTables, "$oldTables")
        val written = HashMap<String, List<Map<String, Any?>>>()
        oldTables.forEachIndexed { t, table ->
            val columns = columnsOf(old, table)
            written[table] = (1..2).map { r ->
                val row = columns.mapIndexed { c, col -> col.name to sampleValue(col.type, t, c, r) }.toMap()
                val names = row.keys.joinToString(", ") { "`$it`" }
                old.execSQL("INSERT INTO `$table` ($names) VALUES (${row.keys.joinToString(", ") { "?" }})", row.values.toTypedArray())
                row
            }
        }
        old.close()

        val room = Room.databaseBuilder(context, DakIndexDatabase::class.java, name)
            .addMigrations(*IndexMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        val migrated = room.openHelper.writableDatabase // runs the migrations and Room's schema validation
        assertEquals(6, migrated.version)
        val dropped = deltas.flatMap { it.replacedTables.keys }.toSet()
        assertTrue(dropped.all { it == Tables.LEDGER_ENTRY }, "only derived ledger entries may be dropped: $dropped")

        for ((table, rows) in written) {
            val stored = readAll(migrated, table)
            if (table in dropped) {
                assertEquals(0, stored.size, "$table is rebuilt by the re-index, not carried over")
                continue
            }
            assertEquals(rows.size, stored.size, "row count of $table")
            val defaults = columnsOf(migrated, table).associate { it.name to it.defaultValue }
            rows.zip(stored).forEach { (before, after) ->
                for ((column, value) in before) {
                    assertEquals(if (value is ByteArray) value.toList() else value, after[column], "$table.$column")
                }
                for (column in after.keys - before.keys) {
                    assertEquals(defaults[column]?.toLongOrNull(), after[column], "new column $table.$column reads its default")
                }
            }
        }

        // The full-text index still finds a message written before the upgrade (by its searchText value).
        val messageRows = written.getValue(Tables.MESSAGE)
        val token = messageRows[1]["searchText"] as String
        migrated.query("SELECT COUNT(*) FROM ${Tables.MESSAGE_FTS} WHERE ${Tables.MESSAGE_FTS} MATCH ?", arrayOf<Any?>(token)).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0), "FTS match for $token")
        }
        room.close()
        context.deleteDatabase(name)
    }

    private class Column(val name: String, val type: String, val defaultValue: String?)

    private fun tableNames(db: SupportSQLiteDatabase): List<String> {
        val out = ArrayList<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                if (!name.startsWith("sqlite_") && name != "android_metadata" && name != "room_master_table") out += name
            }
        }
        return out
    }

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<Column> {
        val out = ArrayList<Column>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val name = c.getColumnIndexOrThrow("name")
            val type = c.getColumnIndexOrThrow("type")
            val default = c.getColumnIndexOrThrow("dflt_value")
            while (c.moveToNext()) out += Column(c.getString(name), c.getString(type).uppercase(), if (c.isNull(default)) null else c.getString(default))
        }
        return out
    }

    /** A value unique to (table, column, row) of the column's type; text values are single FTS tokens. */
    private fun sampleValue(type: String, table: Int, column: Int, row: Int): Any = when {
        type.contains("INT") -> 1_000_000L * (table + 1) + 1_000L * (column + 1) + row
        type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB") -> table + column / 10.0 + row / 100.0
        type.contains("BLOB") -> byteArrayOf(table.toByte(), column.toByte(), row.toByte())
        else -> "v${table}x${column}r$row"
    }

    private fun readAll(db: SupportSQLiteDatabase, table: String): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        db.query("SELECT * FROM `$table` ORDER BY rowid").use { c ->
            while (c.moveToNext()) {
                out += (0 until c.columnCount).associate { i ->
                    c.getColumnName(i) to when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
                        else -> c.getString(i)
                    }
                }
            }
        }
        return out
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
