package app.dak.backup.importers

import app.dak.backup.format.ArchiveLimits
import app.dak.backup.format.checkJsonDepth
import app.dak.backup.format.readBounded
import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import app.dak.core.model.NO_SUB_ID
import java.io.InputStream
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Imports a Fossify Messages JSON export: a top-level array of message objects. Tolerant by
 * design: unknown fields are ignored, and both the older and newer Fossify export shapes are
 * accepted by trying several known field-name spellings per value.
 */
class FossifyImporter : Importer {
    override val id: String = "fossify-messages-export"

    override fun sniff(headerBytes: ByteArray, fileName: String?): Boolean {
        val head = String(headerBytes, Charsets.UTF_8).trimStart()
        if (!head.startsWith("[")) return false
        // Require at least one Fossify-shaped field name so a generic JSON array (e.g. an unknown
        // SMS Organizer export) falls through to the tolerant importer instead of matching here.
        return head.contains("\"address\"") || head.contains("\"subscriptionId\"") ||
            head.contains("\"backupType\"") || head.contains("\"dateSent\"")
    }

    override fun import(input: InputStream): Sequence<ImportedMessage> {
        val text = input.use { readBounded(it, ArchiveLimits.MAX_JSON_IMPORT_BYTES, "Fossify export").toString(Charsets.UTF_8) }
        checkJsonDepth(text)
        val root = Json.parseToJsonElement(text)
        val array = (root as? JsonArray) ?: return emptySequence()
        return array.asSequence().mapNotNull { el -> (el as? JsonObject)?.let(::parseOne) }
    }

    private fun parseOne(obj: JsonObject): ImportedMessage? {
        val body = firstString(obj, "body") ?: ""
        val date = firstLong(obj, "date") ?: return null
        val dateSent = firstLong(obj, "dateSent", "date_sent")
        val boxType = firstInt(obj, "type") ?: 1
        val subId = firstInt(obj, "subscriptionId", "sub_id") ?: NO_SUB_ID
        val read = firstBoolean(obj, "read") ?: true
        val locked = firstBoolean(obj, "locked") ?: false
        val backupType = firstString(obj, "backupType")?.lowercase()
        val parts = obj["parts"]?.let { it as? JsonArray }
        val addressesArray = obj["addresses"]?.let { it as? JsonArray }
        val isMms = backupType == "mms" || parts != null || addressesArray != null

        val kind = if (isMms) MessageKind.MMS else MessageKind.SMS
        val (mmsBody, attachments) = if (parts != null) parseParts(parts) else body to emptyList()
        val resolvedAddress = addressesArray?.let { arr ->
            arr.take(ArchiveLimits.MAX_MMS_ADDRESSES).mapNotNull { addr -> (addr as? JsonObject)?.let { firstString(it, "address") } }
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { null }
        } ?: firstString(obj, "address") ?: return null

        return ImportedMessage(
            kind = kind,
            address = resolvedAddress,
            body = if (isMms) mmsBody else body,
            dateMillis = date,
            dateSentMillis = dateSent,
            box = MessageBox.fromProviderType(boxType),
            subId = subId,
            read = read,
            locked = locked,
            attachments = attachments,
        )
    }

    private fun parseParts(parts: JsonArray): Pair<String, List<ImportedAttachment>> {
        val bodyParts = StringBuilder()
        val attachments = mutableListOf<ImportedAttachment>()
        for (el in parts.take(ArchiveLimits.MAX_MMS_PARTS)) {
            val part = el as? JsonObject ?: continue
            val contentType = firstString(part, "contentType", "ct") ?: ""
            val text = firstString(part, "text")
            val name = firstString(part, "name")
            val data = firstString(part, "data")
            when {
                contentType.startsWith("text/") && text != null -> {
                    if (bodyParts.isNotEmpty()) bodyParts.append('\n')
                    bodyParts.append(text)
                }
                data != null -> {
                    val decoded = runCatching { Base64.getMimeDecoder().decode(data) }.getOrNull()
                    attachments += ImportedAttachment(
                        mimeType = contentType.ifEmpty { "application/octet-stream" },
                        name = name,
                        bytes = decoded?.let { { it } },
                    )
                }
                contentType.isNotEmpty() -> attachments += ImportedAttachment(mimeType = contentType, name = name, text = text)
            }
        }
        return bodyParts.toString() to attachments
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            val v = obj[k] ?: continue
            when (v) {
                is JsonPrimitive -> v.contentOrNull?.let { if (it.isNotEmpty()) return it }
                is JsonArray -> if (v.isNotEmpty()) (v[0] as? JsonPrimitive)?.contentOrNull?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private fun firstLong(obj: JsonObject, vararg keys: String): Long? {
        for (k in keys) {
            (obj[k] as? JsonPrimitive)?.let { p -> p.longOrNull?.let { return it }; p.contentOrNull?.toLongOrNull()?.let { return it } }
        }
        return null
    }

    private fun firstInt(obj: JsonObject, vararg keys: String): Int? = firstLong(obj, *keys)?.toInt()

    private fun firstBoolean(obj: JsonObject, vararg keys: String): Boolean? {
        for (k in keys) {
            val p = obj[k] as? JsonPrimitive ?: continue
            p.booleanOrNull?.let { return it }
            p.longOrNull?.let { return it != 0L }
            p.contentOrNull?.let { s -> s.toBooleanStrictOrNull()?.let { return it } }
        }
        return null
    }
}
