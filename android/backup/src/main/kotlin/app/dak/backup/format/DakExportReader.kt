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
            val zip = ZipInputStream(input)
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name.startsWith("attachments/") -> attachmentSink(name.removePrefix("attachments/"), zip)
                    name.startsWith("messages/") && name.endsWith(".jsonl") -> {
                        val reader = zip.bufferedReader(Charsets.UTF_8)
                        var line = reader.readLine()
                        while (line != null) {
                            if (line.isNotBlank()) yield(json.decodeFromString<MessageRecord>(line))
                            line = reader.readLine()
                        }
                    }
                    name == "threads.json" -> threads = json.decodeFromString(zip.readBytes().toString(Charsets.UTF_8))
                    name == "settings.json" -> settingsJson = zip.readBytes().toString(Charsets.UTF_8)
                    name == "manifest.json" -> manifest = json.decodeFromString(zip.readBytes().toString(Charsets.UTF_8))
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
}
