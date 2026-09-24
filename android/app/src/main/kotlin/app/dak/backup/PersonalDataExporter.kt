package app.dak.backup

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Base64
import app.dak.BuildConfig
import app.dak.automation.UserLabels
import app.dak.backup.format.PersonalDataExportWriter
import app.dak.index.db.DakIndexDatabase
import app.dak.index.repo.SenderMergeRepository
import app.dak.index.sql.Tables
import app.dak.premium.consent.ConsentLedger
import app.dak.settings.SettingsStore
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What an export wrote: section name → rows/items (null for a single JSON object). */
data class PersonalDataExportResult(val sections: Map<String, Int?>)

/**
 * "Export my Dak data" (Settings → Privacy): an unencrypted, user-located ZIP of the data **Dak itself** holds, in the
 * open `dak-personal-data` layout written by `:backup`'s [PersonalDataExportWriter]. It deliberately does not copy the
 * phone's shared SMS/MMS store (other apps own it too, and "Export messages" in Backup already exports it in the open
 * Dak or SMS Backup & Restore format); per-message categories and labels are included, keyed by message, without the
 * message text.
 *
 * Index tables are dumped generically (every column, as stored), so new columns show up without code changes. Secrets
 * are never exported: the index and backup keys, the backup passphrase, the app-lock PIN hash and webhook signing
 * secrets live outside these tables.
 */
@Singleton
class PersonalDataExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: Lazy<DakIndexDatabase>,
    private val settings: SettingsStore,
    private val senderGroups: Lazy<SenderMergeRepository>,
    private val userLabels: UserLabels,
    private val consents: ConsentLedger,
) {
    suspend fun export(uri: Uri): PersonalDataExportResult = withContext(Dispatchers.IO) {
        val written = LinkedHashMap<String, Int?>()
        val out = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write to the chosen file")
        out.use { stream ->
            PersonalDataExportWriter(stream).use { w ->
                w.writeJson("settings", "Your Dak settings (only values you changed).", settings.export())
                written["settings"] = null

                val folds = runCatching { senderGroups.get().exportRules() }.getOrNull()
                if (folds != null) {
                    w.writeJson("sender_groups", "How you grouped senders, and names you gave them.", folds)
                    written["sender_groups"] = null
                }

                val labels = userLabels.all.value.mapValues { (_, v) -> v.sorted() }
                w.writeJson(
                    "user_labels",
                    "Labels you or your rules put on messages, by message key (for example sms:123).",
                    json.encodeToString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), labels),
                    items = labels.size,
                )
                written["user_labels"] = labels.size

                w.writeJson(
                    "consent_records",
                    "Each time you allowed, declined or withdrew a feature that sends data off the phone.",
                    consents.exportJson(),
                    items = consents.records.value.size,
                )
                written["consent_records"] = consents.records.value.size

                for (table in TABLES) {
                    var count = 0
                    w.writeJsonLines(table.section, table.description, rows(table.sql).onEach { count++ })
                    written[table.section] = count
                }
                w.finish(
                    createdAt = System.currentTimeMillis(),
                    appVersion = BuildConfig.VERSION_NAME,
                    readme = README,
                    notIncluded = NOT_INCLUDED,
                )
            }
        }
        PersonalDataExportResult(written)
    }

    /** Each row of [sql] as one compact JSON object. The cursor is closed once the sequence is drained. */
    private fun rows(sql: String): Sequence<String> = sequence {
        val cursor = runCatching { db.get().query(sql, null) }.getOrNull() ?: return@sequence
        cursor.use { c ->
            val names = c.columnNames
            while (c.moveToNext()) {
                val obj = LinkedHashMap<String, JsonElement>(names.size)
                for (i in names.indices) obj[names[i]] = valueAt(c, i)
                yield(json.encodeToString(JsonObject.serializer(), JsonObject(obj)))
            }
        }
    }

    private fun valueAt(c: Cursor, i: Int): JsonElement = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
        Cursor.FIELD_TYPE_BLOB -> JsonPrimitive(Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP))
        else -> JsonPrimitive(c.getString(i))
    }

    private data class TableExport(val section: String, val description: String, val sql: String)

    private companion object {
        val json = Json { encodeDefaults = true }

        val TABLES = listOf(
            TableExport("automation_rules", "Your automation rules (the rule definition is the \"json\" column).", "SELECT * FROM ${Tables.AUTOMATION_RULE}"),
            TableExport("automation_runs", "What each rule sent, failed to send or skipped, and where to.", "SELECT * FROM ${Tables.AUTOMATION_RUN} ORDER BY atMillis"),
            TableExport("activity_log", "Automatic and destructive actions (rules firing, auto-deletes, restores).", "SELECT * FROM ${Tables.AUDIT_LOG} ORDER BY atMillis"),
            TableExport("scheduled_messages", "Messages you scheduled, including sent and cancelled ones.", "SELECT * FROM ${Tables.SCHEDULED_SEND}"),
            TableExport("saved_searches", "Searches you saved.", "SELECT * FROM ${Tables.SAVED_SEARCH}"),
            TableExport("search_history", "Your recent searches.", "SELECT * FROM ${Tables.SEARCH_HISTORY}"),
            TableExport("accounts", "Bank accounts and cards in the Passbook, with balances.", "SELECT * FROM ${Tables.ACCOUNT}"),
            TableExport("ledger", "Transactions Dak found in your messages.", "SELECT * FROM ${Tables.LEDGER_ENTRY}"),
            TableExport("account_links", "Accounts you merged or kept apart.", "SELECT * FROM ${Tables.ACCOUNT_ALIAS}"),
            TableExport("account_types", "Account types you set by hand.", "SELECT * FROM ${Tables.ACCOUNT_TYPE_OVERRIDE}"),
            TableExport("hidden_accounts", "Accounts you removed from the Passbook.", "SELECT * FROM ${Tables.ACCOUNT_HIDDEN}"),
            TableExport("conversation_settings", "Per-conversation choices: pinned, muted, archived, reply SIM, colour.", "SELECT * FROM ${Tables.PREFS}"),
            TableExport("sender_names", "Sender groups and the names shown for them.", "SELECT * FROM ${Tables.MERGE_GROUP}"),
            TableExport("sender_addresses", "Which addresses belong to which sender group.", "SELECT * FROM ${Tables.SENDER_ALIAS}"),
            TableExport("message_flags", "Messages you starred or archived.", "SELECT * FROM ${Tables.MESSAGE_FLAG}"),
            TableExport(
                "message_categories",
                "The category and labels Dak gave each message (no message text).",
                "SELECT kind, providerId, address, dateMillis, category, labels FROM ${Tables.MESSAGE}",
            ),
            TableExport("recycle_bin", "Messages in the recycle bin (deleted, waiting to be purged). Includes their text.", "SELECT * FROM ${Tables.BIN}"),
        )

        val NOT_INCLUDED = listOf(
            "Your SMS and MMS messages themselves: they live in the phone's shared message store. Use Backup → Export to save them.",
            "Keys and secrets: the index key, the backup passphrase and recovery code, the app-lock PIN and webhook secrets.",
            "Data Dak can rebuild: the search index and installed-app OTP hashes.",
        )

        const val README = """Dak personal data export
========================

This file contains the data the Dak app stored about you on your phone, as plain JSON.
It is NOT encrypted: anyone with this file can read it. Keep it somewhere safe and delete it when you are done.

manifest.json lists every file with its size, SHA-256 checksum and a short description.
data/*.json files hold one JSON document each; data/*.jsonl files hold one JSON object per line.
Times are milliseconds since 1970-01-01 UTC.

Not included: your SMS and MMS messages (use Settings > Backup, data and privacy > Export for those),
secrets such as keys and PINs, and data Dak can rebuild from your messages.

Questions: see the privacy policy in the app (Settings > Privacy > Privacy policy).
"""
    }
}
