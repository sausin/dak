package app.dak.index.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.dak.index.db.dao.AppSignatureDao
import app.dak.index.db.dao.AuditLogDao
import app.dak.index.db.dao.AutomationRuleDao
import app.dak.index.db.dao.BackfillStateDao
import app.dak.index.db.dao.BinDao
import app.dak.index.db.dao.ConversationPrefsDao
import app.dak.index.db.dao.LedgerDao
import app.dak.index.db.dao.MessageDao
import app.dak.index.db.dao.RawQueryDao
import app.dak.index.db.dao.SavedSearchDao
import app.dak.index.db.dao.ScheduledSendDao
import app.dak.index.db.dao.SenderMergeDao
import app.dak.index.db.dao.AccountAliasDao
import app.dak.index.db.dao.ConversationAliasDao
import app.dak.index.db.dao.SenderFoldDao
import app.dak.index.db.entity.AccountAliasRow
import app.dak.index.db.entity.AccountRow
import app.dak.index.db.entity.AccountTypeOverrideRow
import app.dak.index.db.entity.AppSignatureRow
import app.dak.index.db.entity.AuditLogRow
import app.dak.index.db.entity.AutomationRuleRow
import app.dak.index.db.entity.BackfillStateRow
import app.dak.index.db.entity.BinEntry
import app.dak.index.db.entity.ConversationAlias
import app.dak.index.db.entity.ConversationPrefs
import app.dak.index.db.entity.IndexedMessage
import app.dak.index.db.entity.LedgerEntryRow
import app.dak.index.db.entity.MessageFlag
import app.dak.index.db.entity.MessageFts
import app.dak.index.db.entity.SavedSearchRow
import app.dak.index.db.entity.ScheduledSendRow
import app.dak.index.db.entity.SearchHistoryRow
import app.dak.index.db.entity.SenderAlias
import app.dak.index.db.entity.SenderFold
import app.dak.index.db.entity.SenderMergeGroup

/**
 * The encrypted index (Room over SQLCipher). Open it only through `IndexDatabaseFactory`, which handles the
 * Keystore-wrapped passphrase. Schema JSON is exported to `core-index/schemas`; every version ships a Room
 * migration ([IndexMigrations]) because the database also holds user data (prefs, bin, saved searches, rules,
 * fold choices, account decisions).
 */
@Database(
    entities = [
        IndexedMessage::class,
        MessageFts::class,
        MessageFlag::class,
        SenderMergeGroup::class,
        SenderAlias::class,
        ConversationPrefs::class,
        BinEntry::class,
        SavedSearchRow::class,
        SearchHistoryRow::class,
        AccountRow::class,
        LedgerEntryRow::class,
        ScheduledSendRow::class,
        AutomationRuleRow::class,
        AuditLogRow::class,
        AppSignatureRow::class,
        BackfillStateRow::class,
        SenderFold::class,
        ConversationAlias::class,
        AccountAliasRow::class,
        AccountTypeOverrideRow::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(IndexConverters::class)
abstract class DakIndexDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun rawQueryDao(): RawQueryDao
    abstract fun conversationPrefsDao(): ConversationPrefsDao
    abstract fun senderMergeDao(): SenderMergeDao
    abstract fun binDao(): BinDao
    abstract fun savedSearchDao(): SavedSearchDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun scheduledSendDao(): ScheduledSendDao
    abstract fun automationRuleDao(): AutomationRuleDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun appSignatureDao(): AppSignatureDao
    abstract fun backfillStateDao(): BackfillStateDao
    abstract fun senderFoldDao(): SenderFoldDao
    abstract fun conversationAliasDao(): ConversationAliasDao
    abstract fun accountAliasDao(): AccountAliasDao

    companion object {
        const val NAME = "dak_index.db"
    }
}
