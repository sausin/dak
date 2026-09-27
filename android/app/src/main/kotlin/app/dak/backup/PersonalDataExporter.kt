package app.dak.backup

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Base64
import androidx.annotation.StringRes
import app.dak.BuildConfig
import app.dak.R
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
        // Descriptions and the README are for the person reading the export: in the app language (the application
        // context follows it, see docs/i18n.md). Section names and file names stay machine ids.
        val out = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write to the chosen file")
        out.use { stream ->
            PersonalDataExportWriter(stream).use { w ->
                w.writeJson("settings", context.getString(R.string.privacy_export_desc_settings), settings.export())
                written["settings"] = null

                val folds = runCatching { senderGroups.get().exportRules() }.getOrNull()
                if (folds != null) {
                    w.writeJson("sender_groups", context.getString(R.string.privacy_export_desc_sender_groups), folds)
                    written["sender_groups"] = null
                }

                val labels = userLabels.all.value.mapValues { (_, v) -> v.sorted() }
                w.writeJson(
                    "user_labels",
                    context.getString(R.string.privacy_export_desc_user_labels),
                    json.encodeToString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), labels),
                    items = labels.size,
                )
                written["user_labels"] = labels.size

                w.writeJson(
                    "consent_records",
                    context.getString(R.string.privacy_export_desc_consent_records),
                    consents.exportJson(),
                    items = consents.records.value.size,
                )
                written["consent_records"] = consents.records.value.size

                for (table in TABLES) {
                    var count = 0
                    w.writeJsonLines(table.section, context.getString(table.description), rows(table.sql).onEach { count++ })
                    written[table.section] = count
                }
                w.finish(
                    createdAt = System.currentTimeMillis(),
                    appVersion = BuildConfig.VERSION_NAME,
                    readme = readme(context),
                    notIncluded = NOT_INCLUDED.map { context.getString(it) },
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

    private data class TableExport(val section: String, @StringRes val description: Int, val sql: String)

    private companion object {
        val json = Json { encodeDefaults = true }

        val TABLES = listOf(
            TableExport("automation_rules", R.string.privacy_export_desc_automation_rules, "SELECT * FROM ${Tables.AUTOMATION_RULE}"),
            TableExport("automation_runs", R.string.privacy_export_desc_automation_runs, "SELECT * FROM ${Tables.AUTOMATION_RUN} ORDER BY atMillis"),
            TableExport("activity_log", R.string.privacy_export_desc_activity_log, "SELECT * FROM ${Tables.AUDIT_LOG} ORDER BY atMillis"),
            TableExport("scheduled_messages", R.string.privacy_export_desc_scheduled_messages, "SELECT * FROM ${Tables.SCHEDULED_SEND}"),
            TableExport("saved_searches", R.string.privacy_export_desc_saved_searches, "SELECT * FROM ${Tables.SAVED_SEARCH}"),
            TableExport("search_history", R.string.privacy_export_desc_search_history, "SELECT * FROM ${Tables.SEARCH_HISTORY}"),
            TableExport("accounts", R.string.privacy_export_desc_accounts, "SELECT * FROM ${Tables.ACCOUNT}"),
            TableExport("ledger", R.string.privacy_export_desc_ledger, "SELECT * FROM ${Tables.LEDGER_ENTRY}"),
            TableExport("account_links", R.string.privacy_export_desc_account_links, "SELECT * FROM ${Tables.ACCOUNT_ALIAS}"),
            TableExport("account_types", R.string.privacy_export_desc_account_types, "SELECT * FROM ${Tables.ACCOUNT_TYPE_OVERRIDE}"),
            TableExport("hidden_accounts", R.string.privacy_export_desc_hidden_accounts, "SELECT * FROM ${Tables.ACCOUNT_HIDDEN}"),
            TableExport("conversation_settings", R.string.privacy_export_desc_conversation_settings, "SELECT * FROM ${Tables.PREFS}"),
            TableExport("sender_names", R.string.privacy_export_desc_sender_names, "SELECT * FROM ${Tables.MERGE_GROUP}"),
            TableExport("sender_addresses", R.string.privacy_export_desc_sender_addresses, "SELECT * FROM ${Tables.SENDER_ALIAS}"),
            TableExport("message_flags", R.string.privacy_export_desc_message_flags, "SELECT * FROM ${Tables.MESSAGE_FLAG}"),
            TableExport(
                "message_categories",
                R.string.privacy_export_desc_message_categories,
                "SELECT kind, providerId, address, dateMillis, category, labels FROM ${Tables.MESSAGE}",
            ),
            TableExport("recycle_bin", R.string.privacy_export_desc_recycle_bin, "SELECT * FROM ${Tables.BIN}"),
        )

        val NOT_INCLUDED = listOf(
            R.string.privacy_export_not_included_messages,
            R.string.privacy_export_not_included_secrets,
            R.string.privacy_export_not_included_rebuildable,
        )

        /** README.txt: a title underlined for plain-text readers, then the body, both in the app language. */
        fun readme(context: Context): String {
            val title = context.getString(R.string.privacy_export_readme_title)
            val body = context.getString(R.string.privacy_export_readme_body)
            return title + "\n" + "=".repeat(title.codePointCount(0, title.length)) + "\n\n" + body.trimEnd() + "\n"
        }
    }
}
