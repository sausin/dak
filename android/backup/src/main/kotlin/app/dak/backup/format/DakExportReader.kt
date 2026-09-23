package app.dak.backup.format

import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Streaming reader for a "Dak export format v1" archive produced by [DakExportWriter].
 *
 * [readMessages] returns a lazy [Sequence] backed directly by the underlying [ZipInputStream], so
 * 100K+ messages never need to fit in memory. `threads.json`, `settings.json` and `manifest.json`
 * are small, single-blob parts of the format and so are buffered in full as they are encountered;
 * their values become available (via [threads], [settingsJson], [manifest]) once the sequence has
 * been iterated past their position in the archive — in the layout [DakExportWriter] produces that
 * means after the sequence is fully drained.
 *
 * Not thread-safe; [readMessages] may only be iterated once.
 */
class DakExportReader(
    private val input: InputStream,
    private val json: Json = DakExportWriter.defaultJson,
) {
    var manifest: Manifest? = null
        private set
    var threads: List<ThreadPrefs> = emptyList()
        private set
    var settingsJson: String? = null
        private set

    /**
     * Lazily parses every `messages/NNNN.jsonl` entry. [attachmentSink] is invoked with each
     * `attachments/<sha256>` entry's name and content stream (valid only for the duration of the
     * call; read it fully before returning) as it is encountered, in archive order.
     */
    fun readMessages(attachmentSink: (sha256: String, input: InputStream) -> Unit = { _, _ -> }): Sequence<MessageRecord> =
        sequence {
            // Archives are untrusted (shared storage, downloads): every entry is size-capped while streaming and
            // attachment names must be bare SHA-256 hex, so a crafted "attachments/../../x" can never become a path.
            var total = 0L
            val countTotal: (Long) -> Unit = { n ->
                total += n
                if (total > ArchiveLimits.MAX_TOTAL_UNCOMPRESSED_BYTES) {
                    throw ArchiveLimitException("archive expands beyond ${ArchiveLimits.MAX_TOTAL_UNCOMPRESSED_BYTES} bytes")
                }
            }
            fun entryStream(zip: ZipInputStream, limit: Long, what: String): InputStream =
                LimitedInputStream(zip, limit, what, countTotal, closeUnderlying = false)

            val zip = ZipInputStream(input)
            var entries = 0
            var entry = zip.nextEntry
            while (entry != null) {
                if (++entries > ArchiveLimits.MAX_ZIP_ENTRIES) throw ArchiveLimitException("more than ${ArchiveLimits.MAX_ZIP_ENTRIES} entries")
                val name = entry.name
                when {
                    name.startsWith("attachments/") -> {
                        val sha = name.removePrefix("attachments/")
                        if (ArchiveLimits.isSha256Hex(sha)) {
                            attachmentSink(sha, entryStream(zip, ArchiveLimits.MAX_ATTACHMENT_BYTES, "attachment"))
                        }
                    }
                    name.startsWith("messages/") && name.endsWith(".jsonl") -> {
                        val reader = entryStream(zip, ArchiveLimits.MAX_MESSAGE_CHUNK_BYTES, name).bufferedReader(Charsets.UTF_8)
                        var line = readBoundedLine(reader)
                        while (line != null) {
                            if (line.isNotBlank()) {
                                checkJsonDepth(line)
                                yield(json.decodeFromString<MessageRecord>(line))
                            }
                            line = readBoundedLine(reader)
                        }
                    }
                    name == "threads.json" -> threads = json.decodeFromString(readMetadata(entryStream(zip, ArchiveLimits.MAX_METADATA_BYTES, name)))
                    name == "settings.json" -> settingsJson = readMetadata(entryStream(zip, ArchiveLimits.MAX_METADATA_BYTES, name))
                    name == "manifest.json" -> manifest = json.decodeFromString(readMetadata(entryStream(zip, ArchiveLimits.MAX_METADATA_BYTES, name)))
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

    private fun readMetadata(input: InputStream): String {
        val text = input.readBytes().toString(Charsets.UTF_8)
        checkJsonDepth(text)
        return text
    }

    /** Like [java.io.BufferedReader.readLine] (split on `\n`, `\r` dropped) but refuses absurdly long lines. */
    private fun readBoundedLine(reader: java.io.Reader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > ArchiveLimits.MAX_JSON_LINE_CHARS) throw ArchiveLimitException("message line too long")
        }
    }
}
