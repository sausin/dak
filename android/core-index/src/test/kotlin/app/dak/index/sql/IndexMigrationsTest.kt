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
    }

    @Test
    fun migration3To4AddsDeliveryColumnsOnly() {
        assertEquals(3, IndexMigrations.MIGRATION_3_4.startVersion)
        assertEquals(4, IndexMigrations.MIGRATION_3_4.endVersion)
        assertEquals(
            listOf(
                "ALTER TABLE `indexed_message` ADD COLUMN `deliveryStatus` INTEGER NOT NULL DEFAULT -1",
                "ALTER TABLE `indexed_message` ADD COLUMN `deliveredAtMillis` INTEGER",
            ),
            IndexMigrations.SQL_3_4,
        )
    }

    @Test
    fun migration4To5AddsTheRunLogOnly() {
        assertEquals(4, IndexMigrations.MIGRATION_4_5.startVersion)
        assertEquals(5, IndexMigrations.MIGRATION_4_5.endVersion)
        val sql = IndexMigrations.SQL_4_5
        assertTrue(sql.none { it.contains("DROP", ignoreCase = true) || it.contains("DELETE", ignoreCase = true) || it.startsWith("ALTER") })
        assertTrue(sql.first().startsWith("CREATE TABLE IF NOT EXISTS `automation_run` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL"))
        assertTrue(
            sql.contains("CREATE INDEX IF NOT EXISTS `index_automation_run_ruleId_atMillis` ON `automation_run` (`ruleId`, `atMillis`)"),
        )
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS `index_automation_run_atMillis` ON `automation_run` (`atMillis`)"))
        assertEquals(
            listOf(IndexMigrations.MIGRATION_1_2, IndexMigrations.MIGRATION_2_3, IndexMigrations.MIGRATION_3_4, IndexMigrations.MIGRATION_4_5),
            IndexMigrations.ALL.toList(),
        )
    }
}
