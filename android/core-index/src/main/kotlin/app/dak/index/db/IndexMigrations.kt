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

    /** SQL of [MIGRATION_4_5], exposed for tests. */
    val SQL_4_5: List<String> = listOf(
        // AutomationRunRow (user data: what automations sent, kept for at least a year).
        "CREATE TABLE IF NOT EXISTS `${Tables.AUTOMATION_RUN}` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`ruleId` TEXT NOT NULL, `ruleName` TEXT NOT NULL, `atMillis` INTEGER NOT NULL, `messageKey` TEXT, " +
            "`conversationId` TEXT, `sourceLabel` TEXT, `actionKind` TEXT NOT NULL, `destinationLabel` TEXT, " +
            "`destination` TEXT, `outcome` TEXT NOT NULL, `reason` TEXT, `textPreview` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_${Tables.AUTOMATION_RUN}_ruleId_atMillis` ON `${Tables.AUTOMATION_RUN}` " +
            "(`ruleId`, `atMillis`)",
        "CREATE INDEX IF NOT EXISTS `index_${Tables.AUTOMATION_RUN}_atMillis` ON `${Tables.AUTOMATION_RUN}` (`atMillis`)",
    )

    /**
     * 4 -> 5: the automation run log (history of forwards, replies and relays per rule). Additive only; it starts
     * empty (earlier forwards are only in the 90-day audit log).
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in SQL_4_5) db.execSQL(sql)
        }
    }

    /** SQL of [MIGRATION_5_6], exposed for tests. */
    val SQL_5_6: List<String> = listOf(
        // LedgerEntryRow: own-account / investment transfers (never spending) and investment details.
        "ALTER TABLE `${Tables.LEDGER_ENTRY}` ADD COLUMN `transfer` INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE `${Tables.LEDGER_ENTRY}` ADD COLUMN `investmentAction` TEXT",
        "ALTER TABLE `${Tables.LEDGER_ENTRY}` ADD COLUMN `units` TEXT",
        "ALTER TABLE `${Tables.LEDGER_ENTRY}` ADD COLUMN `unitPrice` TEXT",
        // AccountRow.unitsHeld (investment accounts).
        "ALTER TABLE `${Tables.ACCOUNT}` ADD COLUMN `unitsHeld` TEXT",
    )

    /**
     * 5 -> 6: investments in the Passbook (mutual-fund folios, demat accounts). Additive only: existing ledger entries
     * read "not a transfer" with no investment details, and existing accounts no units, until the next ledger
     * recompute (the re-index that ships with the parser change) fills them in.
     */
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in SQL_5_6) db.execSQL(sql)
        }
    }

    /** SQL of [MIGRATION_6_7], exposed for tests. */
    val SQL_6_7: List<String> = listOf(
        // ConversationPrefs.incognitoSince (vanishing chats; null = off).
        "ALTER TABLE `${Tables.PREFS}` ADD COLUMN `incognitoSince` INTEGER",
        // AccountHiddenRow: accounts the user removed from the Passbook.
        "CREATE TABLE IF NOT EXISTS `${Tables.ACCOUNT_HIDDEN}` (`accountId` TEXT NOT NULL, `hiddenAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`accountId`))",
    )

    /**
     * 6 -> 7: incognito conversations and hidden Passbook accounts. Additive only: every existing conversation reads
     * "not incognito" and no account starts hidden.
     */
    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in SQL_6_7) db.execSQL(sql)
        }
    }

    /** Name of the inbox list's covering index (Room's `index_<table>_<columns>` scheme). */
    const val INBOX_COVERING_INDEX: String =
        "index_${Tables.MESSAGE}_conversationId_dateMillis_category_subId_archived_starred_read_box_threadId"

    /** SQL of [MIGRATION_7_8], exposed for tests. */
    val SQL_7_8: List<String> = listOf(
        // IndexedMessage's covering index for the inbox list (ConversationSqlBuilder.page / count).
        "CREATE INDEX IF NOT EXISTS `$INBOX_COVERING_INDEX` ON `${Tables.MESSAGE}` " +
            "(`conversationId`, `dateMillis`, `category`, `subId`, `archived`, `starred`, `read`, `box`, `threadId`)",
    )

    /**
     * 7 -> 8: a covering index so paging the inbox never reads message rows (docs/performance.md, "Ledger and
     * inbox"). Additive only; SQLite builds it from the existing rows during the upgrade.
     */
    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in SQL_7_8) db.execSQL(sql)
        }
    }

    val ALL: Array<Migration> =
        arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
}
