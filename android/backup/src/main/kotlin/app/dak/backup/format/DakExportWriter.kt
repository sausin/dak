package app.dak.backup.format

import app.dak.backup.format.Hashing.toHex
import java.io.BufferedWriter
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Streaming writer for a "Dak export format v1" archive (see `FORMAT.md`).
 *
 * Entries are written in this fixed order so a single forward pass can produce and later consume
 * the archive without random access:
 * 1. `attachments/<sha256>` (zero or more, via [writeAttachment])
 * 2. `messages/NNNN.jsonl` chunks (via [writeMessages])
 * 3. `threads.json` (via [writeThreads])
 * 4. `settings.json` (via [writeSettings])
 * 5. `manifest.json`, written last by [finish] once every other part's size and SHA-256 are known.
 *
 * Not thread-safe; call the `write*` methods in the order above, then [finish], then [close].
 */
class DakExportWriter(
    output: OutputStream,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val json: Json = defaultJson,
) : Closeable {

    private val zip = ZipOutputStream(output)
    private val parts = mutableListOf<ManifestPart>()
    private var messageCount = 0
    private var threadCount = 0
    private var attachmentCount = 0
    private var finished = false

    /** Writes one content-addressed attachment blob. [sha256] must be the lower-case hex digest of [bytes]. */
    fun writeAttachment(sha256: String, bytes: ByteArray) {
        writeEntry("attachments/$sha256") { it.write(bytes) }
        attachmentCount++
    }

    /** Streaming variant of [writeAttachment] for large blobs; [input] is read fully and closed. */
    fun writeAttachment(sha256: String, input: InputStream) {
        writeEntry("attachments/$sha256") { out -> input.use { it.copyTo(out) } }
        attachmentCount++
    }

    /**
     * Writes [messages] in chunks of [chunkSize] lines per `messages/NNNN.jsonl` entry. [messages] is
     * consumed exactly once and lazily, so an arbitrarily large sequence never needs to fit in memory.
     */
    fun writeMessages(messages: Sequence<MessageRecord>) {
        val iterator = messages.iterator()
        var index = 0
        while (iterator.hasNext()) {
            val name = "messages/%04d.jsonl".format(index)
            writeEntry(name) { out ->
                val writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
                var inChunk = 0
                while (inChunk < chunkSize && iterator.hasNext()) {
                    val record = iterator.next()
                    writer.write(json.encodeToString(record))
                    writer.write("\n")
                    inChunk++
                    messageCount++
                }
                writer.flush() // never close: that would close the shared zip stream
            }
            index++
        }
    }

    fun writeThreads(threads: List<ThreadPrefs>) {
        writeEntry("threads.json") { it.write(json.encodeToString(threads).toByteArray(Charsets.UTF_8)) }
        threadCount = threads.size
    }

    /** [settingsJson] is passed through opaquely; the backup module does not interpret settings. */
    fun writeSettings(settingsJson: String) {
        writeEntry("settings.json") { it.write(settingsJson.toByteArray(Charsets.UTF_8)) }
    }

    /** Writes the trailer `manifest.json` entry and returns the [Manifest] that was written. */
    fun finish(meta: ManifestMeta): Manifest {
        check(!finished) { "finish() already called" }
        val manifest = Manifest(
            createdAt = meta.createdAt,
            appVersion = meta.appVersion,
            device = meta.device,
            counts = ManifestCounts(messageCount, threadCount, attachmentCount),
            parts = parts.toList(),
            id = meta.id,
            parentId = meta.parentId,
            kind = meta.kind,
            deletedKeys = meta.deletedKeys,
        )
        val bytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        zip.putNextEntry(ZipEntry("manifest.json"))
        zip.write(bytes)
        zip.closeEntry()
        finished = true
        return manifest
    }

    override fun close() {
        zip.close()
    }

    private fun writeEntry(name: String, block: (OutputStream) -> Unit) {
        check(!finished) { "cannot write $name after finish()" }
        val digest = MessageDigest.getInstance("SHA-256")
        zip.putNextEntry(ZipEntry(name))
        val counting = CountingOutputStream(DigestOutputStream(NonClosingOutputStream(zip), digest))
        block(counting)
        zip.closeEntry()
        parts += ManifestPart(name = name, sha256 = digest.digest().toHex(), sizeBytes = counting.count)
    }

    private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        override fun close() { flush() } // deliberately does not close the underlying ZipOutputStream
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 1000
        internal val defaultJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
