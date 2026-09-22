package app.dak.backup.importers

import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import app.dak.core.model.NO_SUB_ID
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * SMS Organizer's Drive backup format is not publicly documented (see the build plan's "Bring to
 * the first build session": a real backup is needed to verify this against). This importer is a
 * best-effort, tolerant reader: it accepts either a ZIP (unwrapping the first JSON entry it finds)
 * or a bare JSON file, then tries a handful of known-plausible shapes with case-insensitive key
 * matching and either epoch-millisecond or epoch-second dates. **Treat its output as provisional
 * until checked against a genuine SMS Organizer backup.**
 */
class SmsOrganizerImporter {

    /** Sniffs a ZIP or a JSON array/object; deliberately permissive since the real format is unknown. */
    fun sniff(headerBytes: ByteArray, fileName: String? = null): Boolean {
        if (headerBytes.size >= 4 && headerBytes[0] == 0x50.toByte() && headerBytes[1] == 0x4B.toByte()) return true // "PK"
        val head = String(headerBytes, Charsets.UTF_8).trimStart()
        return head.startsWith("[") || head.startsWith("{")
    }

    /** Parses [input] (owned and closed by this call) into an [ImportResult]. */
    fun import(input: InputStream, fileNameHint: String? = null): ImportResult {
        val bytes = input.use { it.readBytes() }
        val looksLikeZip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        val jsonText = if (looksLikeZip) extractFirstJsonEntry(bytes) else bytes.toString(Charsets.UTF_8)
        if (jsonText == null) {
            return ImportResult(emptyList(), warnings = listOf("No JSON payload found in the SMS Organizer backup archive"))
        }
        return parseJson(jsonText)
    }

    private fun extractFirstJsonEntry(zipBytes: ByteArray): String? {
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true)) {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun parseJson(text: String): ImportResult {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return ImportResult(emptyList(), warnings = listOf("Could not parse JSON: ${e.message}"))
        }
        // Known-plausible shapes: a bare array of messages, or an object with a "messages"/"sms"/"data" array.
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["messages"] ?: root["sms"] ?: root["data"] ?: root["items"]) as? JsonArray
            else -> null
        }
        if (array == null) {
            return ImportResult(emptyList(), warnings = listOf("Unrecognized SMS Organizer backup shape (expected a JSON array of messages)"))
        }
        val messages = mutableListOf<ImportedMessage>()
        var skipped = 0
        val warnings = mutableListOf<String>()
        for (el in array) {
            val obj = el as? JsonObject
            if (obj == null) { skipped++; continue }
            val parsed = parseOne(obj)
            if (parsed == null) skipped++ else messages += parsed
        }
        if (skipped > 0) warnings += "$skipped entr${if (skipped == 1) "y" else "ies"} could not be interpreted and were skipped"
        return ImportResult(messages, skipped, warnings)
    }

    private fun parseOne(obj: JsonObject): ImportedMessage? {
        val lower = obj.entries.associate { (k, v) -> k.lowercase() to v }
        val address = firstString(lower, "address", "sender", "number", "phone", "from") ?: return null
        val body = firstString(lower, "body", "message", "text", "content") ?: ""
        val rawDate = firstLong(lower, "date", "timestamp", "time", "datesent") ?: return null
        val dateMillis = normalizeEpoch(rawDate)
        val typeStr = firstString(lower, "type", "messagetype", "folder")
        val box = inferBox(typeStr, lower)
        val subId = firstLong(lower, "subid", "sub_id", "simid", "sim_slot")?.toInt() ?: NO_SUB_ID
        val read = firstBoolean(lower, "read") ?: true
        val kind = if (firstBoolean(lower, "ismms") == true || lower.containsKey("parts")) MessageKind.MMS else MessageKind.SMS

        return ImportedMessage(
            kind = kind,
            address = address,
            body = body,
            dateMillis = dateMillis,
            box = box,
            subId = subId,
            read = read,
        )
    }

    private fun inferBox(typeStr: String?, obj: Map<String, kotlinx.serialization.json.JsonElement>): MessageBox {
        val numeric = typeStr?.toIntOrNull() ?: firstLong(obj, "type")?.toInt()
        if (numeric != null) return MessageBox.fromProviderType(numeric)
        return when (typeStr?.lowercase()) {
            "sent" -> MessageBox.SENT
            "draft" -> MessageBox.DRAFT
            "outbox" -> MessageBox.OUTBOX
            "failed" -> MessageBox.FAILED
            "queued" -> MessageBox.QUEUED
            else -> MessageBox.INBOX
        }
    }

    /** SMS Organizer's real epoch unit is unverified; treat a value under ~13 digits as seconds, not millis. */
    private fun normalizeEpoch(value: Long): Long = if (value in 1_000_000_000..9_999_999_999L) value * 1000 else value

    private fun firstString(obj: Map<String, kotlinx.serialization.json.JsonElement>, vararg keys: String): String? {
        for (k in keys) (obj[k] as? JsonPrimitive)?.contentOrNull?.let { if (it.isNotEmpty()) return it }
        return null
    }

    private fun firstLong(obj: Map<String, kotlinx.serialization.json.JsonElement>, vararg keys: String): Long? {
        for (k in keys) {
            val p = obj[k] as? JsonPrimitive ?: continue
            p.longOrNull?.let { return it }
            p.contentOrNull?.toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun firstBoolean(obj: Map<String, kotlinx.serialization.json.JsonElement>, vararg keys: String): Boolean? {
        for (k in keys) {
            val p = obj[k] as? JsonPrimitive ?: continue
            p.contentOrNull?.let { s ->
                s.toBooleanStrictOrNull()?.let { return it }
                s.toLongOrNull()?.let { return it != 0L }
            }
        }
        return null
    }
}
