package app.dak.index.sql

/**
 * Table names of the index database, shared by the Room entities and the pure SQL builders so the two can never
 * drift apart. Column names are always the entity property names (no `@ColumnInfo(name = ...)` renames).
 */
object Tables {
    const val MESSAGE = "indexed_message"
    const val MESSAGE_FTS = "message_fts"
    const val MESSAGE_FLAG = "message_flag"
    const val MERGE_GROUP = "sender_merge_group"
    const val SENDER_ALIAS = "sender_alias"
    const val PREFS = "conversation_prefs"
    const val BIN = "bin_entry"
    const val SAVED_SEARCH = "saved_search"
    const val SEARCH_HISTORY = "search_history"
    const val LEDGER_ENTRY = "ledger_entry"
    const val ACCOUNT = "ledger_account"
    const val SCHEDULED_SEND = "scheduled_send"
    const val AUTOMATION_RULE = "automation_rule"
    const val AUDIT_LOG = "audit_log"
    const val APP_SIGNATURE = "app_signature"
    const val BACKFILL_STATE = "backfill_state"
    const val SENDER_FOLD = "sender_fold"
    const val CONVERSATION_ALIAS = "conversation_alias"
    const val ACCOUNT_ALIAS = "account_alias"
    const val ACCOUNT_TYPE_OVERRIDE = "account_type_override"
    const val AUTOMATION_RUN = "automation_run"
    const val ACCOUNT_HIDDEN = "account_hidden"
}

/** A SQL statement plus its positional bind arguments (only [String], [Long] and [Double] values). */
data class SqlQuery(val sql: String, val args: List<Any>)

/** Escapes `%`, `_` and `\` for a `LIKE ... ESCAPE '\'` pattern. */
internal fun escapeLike(value: String): String = buildString(value.length + 4) {
    for (c in value) {
        if (c == '%' || c == '_' || c == '\\') append('\\')
        append(c)
    }
}

internal fun likeContains(value: String): String = "%" + escapeLike(value) + "%"
