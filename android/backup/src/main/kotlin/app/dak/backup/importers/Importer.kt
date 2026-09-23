package app.dak.backup.importers

import app.dak.core.model.MessageBox
import app.dak.core.model.MessageKind
import app.dak.core.model.NO_SUB_ID
import java.io.InputStream

/** One attachment/part recovered from a foreign backup, before it is written into the provider. */
data class ImportedAttachment(
    val mimeType: String,
    val name: String? = null,
    /** Inline text part (MMS SMIL text parts, `text/plain` parts with no binary data). */
    val text: String? = null,
    /** Lazily reads the binary payload; null for text-only parts. Callers should read this once. */
    val bytes: (() -> ByteArray)? = null,
)

/** One message recovered from a foreign backup, in a shape close to `core-model.Message`. */
data class ImportedMessage(
    val kind: MessageKind,
    val address: String,
    val body: String,
    val dateMillis: Long,
    val dateSentMillis: Long? = null,
    val box: MessageBox = MessageBox.INBOX,
    val subId: Int = NO_SUB_ID,
    val read: Boolean = true,
    val locked: Boolean = false,
    val contactName: String? = null,
    val attachments: List<ImportedAttachment> = emptyList(),
)

/** The result of a non-streaming (buffer-everything) import, used by importers over unknown/tolerant formats. */
data class ImportResult(
    val messages: List<ImportedMessage>,
    val skipped: Int = 0,
    val warnings: List<String> = emptyList(),
)

/**
 * Common interface for a foreign-backup importer. Implementations should stream where the source
 * format allows it (see [app.dak.backup.importers.SmsBackupRestoreXmlImporter]); formats with no
 * reliable schema may buffer instead (see [SmsOrganizerImporter]).
 */
interface Importer {
    /** Short, stable identifier, e.g. "sms-backup-restore-xml". */
    val id: String

    /** Cheap, best-effort check: does this file look like this importer's format? */
    fun sniff(headerBytes: ByteArray, fileName: String? = null): Boolean

    /**
     * Parses [input] (which this call owns and should close) into a sequence of [ImportedMessage].
     * Streaming importers yield lazily; tolerant/whole-file importers may build the list eagerly
     * and return `list.asSequence()`.
     */
    fun import(input: InputStream): Sequence<ImportedMessage>
}
