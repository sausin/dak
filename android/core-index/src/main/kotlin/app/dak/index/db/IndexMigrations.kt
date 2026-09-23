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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
