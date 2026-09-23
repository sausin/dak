package app.dak.index.sql

import app.dak.index.db.IndexMigrations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** String-level checks of the migrations (the Robolectric test compares them with Room's own schema). */
class IndexMigrationsTest {

    @Test
    fun migrationIsAdditiveOnly() {
        val sql = IndexMigrations.SQL_1_2
        assertTrue(sql.none { it.contains("DROP", ignoreCase = true) || it.contains("DELETE", ignoreCase = true) })
        assertEquals(1, IndexMigrations.MIGRATION_1_2.startVersion)
        assertEquals(2, IndexMigrations.MIGRATION_1_2.endVersion)
    }

    @Test
    fun newIndexUsesRoomsNamingScheme() {
        assertTrue(
            IndexMigrations.SQL_1_2.contains(
                "CREATE INDEX IF NOT EXISTS `index_indexed_message_repeatGroup` ON `indexed_message` (`repeatGroup`)",
            ),
        )
    }

    @Test
    fun migration2To3DropsOnlyDerivedLedgerEntries() {
        val sql = IndexMigrations.SQL_2_3
        assertEquals(2, IndexMigrations.MIGRATION_2_3.startVersion)
        assertEquals(3, IndexMigrations.MIGRATION_2_3.endVersion)
        val drops = sql.filter { it.contains("DROP", ignoreCase = true) || it.contains("DELETE", ignoreCase = true) }
        assertEquals(listOf("DROP TABLE IF EXISTS `ledger_entry`"), drops)
        assertTrue(sql.any { it.contains("PRIMARY KEY(`accountId`, `messageKey`)") })
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS `index_ledger_entry_accountId_dateMillis` ON `ledger_entry` (`accountId`, `dateMillis`)"))
        assertTrue(sql.any { it.startsWith("CREATE TABLE IF NOT EXISTS `account_type_override`") })
        assertEquals(listOf(IndexMigrations.MIGRATION_1_2, IndexMigrations.MIGRATION_2_3), IndexMigrations.ALL.toList())
    }
}
