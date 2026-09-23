package app.dak.index.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.dak.index.sql.Tables

/**
 * Schema migrations of [DakIndexDatabase]. The index holds user data (prefs, bin, rules, fold choices, account
 * decisions), so upgrades are never destructive. Every statement must produce exactly what Room generates for the
 * entities (column types, NOT NULL, primary keys, index names), or Room's open-time validation fails.
 */
object IndexMigrations {

    /** SQL of [MIGRATION_1_2], exposed for tests. */
    val SQL_1_2: List<String> = listOf(
        // Repeated-message collapse (IndexedMessage.repeatGroup + its index).
        "ALTER TABLE `${Tables.MESSAGE}` ADD COLUMN `repeatGroup` TEXT",
        "CREATE INDEX IF NOT EXISTS `index_${Tables.MESSAGE}_repeatGroup` ON `${Tables.MESSAGE}` (`repeatGroup`)",
        // AccountRow.maskedNumber.
        "ALTER TABLE `${Tables.ACCOUNT}` ADD COLUMN `maskedNumber` TEXT",
        // SenderFold.
        "CREATE TABLE IF NOT EXISTS `${Tables.SENDER_FOLD}` (`channel` TEXT NOT NULL, `groupKey` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`channel`))",
        // ConversationAlias.
        "CREATE TABLE IF NOT EXISTS `${Tables.CONVERSATION_ALIAS}` (`oldId` TEXT NOT NULL, `newId` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`oldId`))",
        // AccountAliasRow.
        "CREATE TABLE IF NOT EXISTS `${Tables.ACCOUNT_ALIAS}` (`aliasId` TEXT NOT NULL, `canonicalId` TEXT NOT NULL, " +
            "`same` INTEGER NOT NULL, `decidedAt` INTEGER NOT NULL, PRIMARY KEY(`aliasId`, `canonicalId`))",
    )

    /**
     * 1 -> 2: sender folds, conversation id aliases, repeated-message groups, account alias decisions and the
     * account's masked number. Existing rows get NULL in the new columns; the enricher version bump that ships
     * with it re-indexes every message, which fills them in.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in SQL_1_2) db.execSQL(sql)
        }
    }

    /** SQL of [MIGRATION_2_3], exposed for tests. */
    val SQL_2_3: List<String> = listOf(
        // AccountRow.linkedAccountId (debit card / loan -> the bank account an SMS named with it).
        "ALTER TABLE `${Tables.ACCOUNT}` ADD COLUMN `linkedAccountId` TEXT",
        // LedgerEntryRow is derived data: recreated with the (accountId, messageKey) key and viaAccountId, and
        // refilled by the re-index (enricher LOGIC_REVISION 4) that ships with this version.
        "DROP TABLE IF EXISTS `${Tables.LEDGER_ENTRY}`",
        "CREATE TABLE IF NOT EXISTS `${Tables.LEDGER_ENTRY}` (`messageKey` TEXT NOT NULL, `accountId` TEXT NOT NULL, " +
            "`dateMillis` INTEGER NOT NULL, `direction` TEXT NOT NULL, `originalMinor` INTEGER NOT NULL, " +
            "`originalCurrency` TEXT NOT NULL, `indicativeMinor` INTEGER, `indicativeCurrency` TEXT, `rate` TEXT, " +
            "`rateDateMillis` INTEGER, `settled` INTEGER NOT NULL, `markupPercent` TEXT, `balanceAfterMinor` INTEGER, " +
            "`balanceAfterCurrency` TEXT, `merchant` TEXT, `reference` TEXT, `viaAccountId` TEXT, " +
            "PRIMARY KEY(`accountId`, `messageKey`))",
        "CREATE INDEX IF NOT EXISTS `index_${Tables.LEDGER_ENTRY}_accountId_dateMillis` ON `${Tables.LEDGER_ENTRY}` " +
            "(`accountId`, `dateMillis`)",
        // AccountTypeOverrideRow (user data).
        "CREATE TABLE IF NOT EXISTS `${Tables.ACCOUNT_TYPE_OVERRIDE}` (`accountId` TEXT NOT NULL, " +
            "`instrument` TEXT NOT NULL, `decidedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`))",
    )

    /**
     * 2 -> 3: account instrument groups (debit/prepaid/loan). Adds the linked bank account of a debit card or loan,
     * the user's manual account types, and re-keys the derived ledger entries so one message can post to a card and
     * the bank account it names. Only derived data (ledger entries) is dropped; user data is untouched.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in SQL_2_3) db.execSQL(sql)
        }
    }

    /** SQL of [MIGRATION_3_4], exposed for tests. */
    val SQL_3_4: List<String> = listOf(
        // IndexedMessage.deliveryStatus (DeliveryStatus.code, -1 = none) and deliveredAtMillis: delivery ticks.
        "ALTER TABLE `${Tables.MESSAGE}` ADD COLUMN `deliveryStatus` INTEGER NOT NULL DEFAULT -1",
        "ALTER TABLE `${Tables.MESSAGE}` ADD COLUMN `deliveredAtMillis` INTEGER",
    )

    /**
     * 3 -> 4: delivery reports (double ticks). Additive only; existing rows read "no report" until the reconcile
     * refreshes recent outgoing messages or a re-index rewrites them.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in SQL_3_4) db.execSQL(sql)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
