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
    }

    @Test
    fun migration5To6AddsInvestmentColumnsOnly() {
        assertEquals(5, IndexMigrations.MIGRATION_5_6.startVersion)
        assertEquals(6, IndexMigrations.MIGRATION_5_6.endVersion)
        assertEquals(
            listOf(
                "ALTER TABLE `ledger_entry` ADD COLUMN `transfer` INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE `ledger_entry` ADD COLUMN `investmentAction` TEXT",
                "ALTER TABLE `ledger_entry` ADD COLUMN `units` TEXT",
                "ALTER TABLE `ledger_entry` ADD COLUMN `unitPrice` TEXT",
                "ALTER TABLE `ledger_account` ADD COLUMN `unitsHeld` TEXT",
            ),
            IndexMigrations.SQL_5_6,
        )
    }

    @Test
    fun migration6To7AddsIncognitoAndHiddenAccountsOnly() {
        assertEquals(6, IndexMigrations.MIGRATION_6_7.startVersion)
        assertEquals(7, IndexMigrations.MIGRATION_6_7.endVersion)
        assertEquals(
            listOf(
                "ALTER TABLE `conversation_prefs` ADD COLUMN `incognitoSince` INTEGER",
                "CREATE TABLE IF NOT EXISTS `account_hidden` (`accountId` TEXT NOT NULL, `hiddenAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`accountId`))",
            ),
            IndexMigrations.SQL_6_7,
        )
        assertEquals(
            listOf(
                IndexMigrations.MIGRATION_1_2, IndexMigrations.MIGRATION_2_3, IndexMigrations.MIGRATION_3_4, IndexMigrations.MIGRATION_4_5,
                IndexMigrations.MIGRATION_5_6, IndexMigrations.MIGRATION_6_7,
            ),
            IndexMigrations.ALL.toList(),
        )
    }
}
