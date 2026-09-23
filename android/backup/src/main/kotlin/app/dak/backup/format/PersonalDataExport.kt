package app.dak.backup.format

import app.dak.backup.format.Hashing.toHex
import java.io.BufferedWriter
import java.io.Closeable
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val PERSONAL_DATA_FORMAT_NAME: String = "dak-personal-data"
const val PERSONAL_DATA_FORMAT_VERSION: Int = 1

/** One section of a personal-data export: `data/<name>.json` or `data/<name>.jsonl`. */
@Serializable
data class PersonalDataPart(
    val name: String,
    val sha256: String,
    val sizeBytes: Long,
    /** Rows (JSONL) or top-level items (JSON array); null for a JSON object. */
    val items: Int? = null,
    val description: String,
)

@Serializable
data class PersonalDataManifest(
    val format: String = PERSONAL_DATA_FORMAT_NAME,
    val version: Int = PERSONAL_DATA_FORMAT_VERSION,
    val createdAt: Long,
    val appVersion: String,
    val parts: List<PersonalDataPart>,
    /** What is deliberately not in this file, in plain words (for example the phone's own SMS store). */
    val notIncluded: List<String> = emptyList(),
)

/**
 * "Export my data" (GDPR Art. 15 and 20, DPDP Act 2023 s.11): an unencrypted ZIP of the data Dak itself holds about
 * the user, in plain JSON, written to a place the user picked. Layout (see `backup/FORMAT.md`):
 *
 * ```
 * README.txt               what each file is, in plain language
 * data/<section>.json      one JSON document per section (settings, rules, accounts, ...)
 * data/<section>.jsonl     one JSON object per line for long sections (run history, ledger, ...)
 * manifest.json            written last: every part's SHA-256, size and item count
 * ```
 *
 * The app decides what goes in each section; this writer only enforces safe names, bounds and the layout. Not
 * thread-safe; call [writeJson] / [writeJsonLines] in any order, then [finish], then [close].
 */
class PersonalDataExportWriter(output: OutputStream) : Closeable {
    private val zip = ZipOutputStream(output)
    private val parts = mutableListOf<PersonalDataPart>()
    private val names = HashSet<String>()
    private var finished = false

    /** Writes `data/<section>.json` holding [jsonText] (already-encoded JSON). */
    fun writeJson(section: String, description: String, jsonText: String, items: Int? = null) {
        val name = entryName(section, "json")
        val bytes = jsonText.toByteArray(Charsets.UTF_8)
        if (bytes.size > ArchiveLimits.MAX_METADATA_BYTES) throw ArchiveLimitException("$name exceeds ${ArchiveLimits.MAX_METADATA_BYTES} bytes")
        writeEntry(name, description, items) { it.write(bytes) }
    }

    /** Writes `data/<section>.jsonl`, one already-encoded JSON object per element of [lines], at most [maxLines]. */
    fun writeJsonLines(section: String, description: String, lines: Sequence<String>, maxLines: Int = MAX_LINES_PER_SECTION) {
        val name = entryName(section, "jsonl")
        var count = 0
        writeEntry(name, description, itemsProvider = { count }) { out ->
            val writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
            for (line in lines) {
                if (count >= maxLines) break
                require('\n' !in line && '\r' !in line) { "JSON lines must not contain line breaks" }
                writer.write(line)
                writer.write("\n")
                count++
            }
            writer.flush()
        }
    }

    /** Writes `README.txt` and the trailer `manifest.json`; returns the manifest. */
    fun finish(createdAt: Long, appVersion: String, readme: String, notIncluded: List<String> = emptyList()): PersonalDataManifest {
        check(!finished) { "finish() already called" }
        zip.putNextEntry(ZipEntry("README.txt"))
        zip.write(readme.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        val manifest = PersonalDataManifest(createdAt = createdAt, appVersion = appVersion, parts = parts.toList(), notIncluded = notIncluded)
        zip.putNextEntry(ZipEntry("manifest.json"))
        zip.write(prettyJson.encodeToString(manifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        finished = true
        return manifest
    }

    override fun close() {
        zip.close()
    }

    private fun entryName(section: String, extension: String): String {
        require(SECTION.matches(section)) { "Invalid section name: $section" }
        val name = "data/$section.$extension"
        require(names.add(section)) { "Section $section written twice" }
        return name
    }

    private fun writeEntry(name: String, description: String, items: Int?, block: (OutputStream) -> Unit) =
        writeEntry(name, description, { items }, block)

    private fun writeEntry(name: String, description: String, itemsProvider: () -> Int?, block: (OutputStream) -> Unit) {
        check(!finished) { "cannot write $name after finish()" }
        val digest = MessageDigest.getInstance("SHA-256")
        zip.putNextEntry(ZipEntry(name))
        val counting = CountingOutputStream(DigestOutputStream(NonClosingOutputStream(zip), digest))
        block(counting)
        zip.closeEntry()
        parts += PersonalDataPart(
            name = name,
            sha256 = digest.digest().toHex(),
            sizeBytes = counting.count,
            items = itemsProvider(),
            description = description,
        )
    }

    private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        override fun close() { flush() }
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
        /** Rows per JSONL section (the largest, run history and ledger, stay far below this). */
        const val MAX_LINES_PER_SECTION: Int = 1_000_000
        private val SECTION = Regex("^[a-z][a-z0-9_]{0,63}$")
        private val prettyJson = Json { encodeDefaults = true; prettyPrint = true }
    }
}
