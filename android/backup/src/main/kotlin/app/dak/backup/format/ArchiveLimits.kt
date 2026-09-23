package app.dak.backup.format

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Thrown when a backup or import file exceeds a safety limit (zip bomb, oversized entry, absurd nesting, …).
 * An [IOException] so existing "the file could not be read" handling applies.
 */
class ArchiveLimitException(message: String) : IOException(message)

/**
 * Resource limits for every file the user imports or restores. Backup files live on shared storage (Drive, a
 * SAF folder, a download) and foreign exports come from anywhere, so they are treated as attacker-controlled:
 * sizes are enforced while streaming, never trusted from headers.
 */
object ArchiveLimits {
    /** Entries per Dak archive (attachments are one entry each; messages are chunked 1000 per entry). */
    const val MAX_ZIP_ENTRIES: Int = 200_000

    /** Uncompressed bytes of one attachment entry (larger than any MMS a carrier will carry). */
    const val MAX_ATTACHMENT_BYTES: Long = 64L * 1024 * 1024

    /** Uncompressed bytes of a small JSON entry (`threads.json`, `settings.json`, `manifest.json`). */
    const val MAX_METADATA_BYTES: Long = 16L * 1024 * 1024

    /** Uncompressed bytes of one `messages/NNNN.jsonl` chunk. */
    const val MAX_MESSAGE_CHUNK_BYTES: Long = 256L * 1024 * 1024

    /** Characters in one JSONL message line. */
    const val MAX_JSON_LINE_CHARS: Int = 8 * 1024 * 1024

    /** Total uncompressed bytes read from one archive: stops zip bombs that stay under every per-entry cap. */
    const val MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 8L * 1024 * 1024 * 1024

    /** Whole-file JSON imports (Fossify, SMS Organizer) are parsed in memory: cap what is buffered. */
    const val MAX_JSON_IMPORT_BYTES: Long = 128L * 1024 * 1024

    /** Maximum JSON nesting depth accepted from imported files (the formats need fewer than 10 levels). */
    const val MAX_JSON_DEPTH: Int = 64

    /** Incremental chain length followed on restore (also stops parent-id cycles). */
    const val MAX_CHAIN_LENGTH: Int = 10_000

    /** Rows of automation run history carried in one archive (the app keeps a year, at least 5,000 rows). */
    const val MAX_AUTOMATION_RUNS: Int = 100_000

    /** Uncompressed bytes of `automation_runs.jsonl`. */
    const val MAX_AUTOMATION_RUNS_BYTES: Long = 128L * 1024 * 1024

    /** Characters in one `automation_runs.jsonl` line. */
    const val MAX_AUTOMATION_RUN_LINE_CHARS: Int = 64 * 1024

    /** Characters kept per string field of a restored automation run (longer values are cut). */
    const val MAX_AUTOMATION_RUN_FIELD_CHARS: Int = 2_000

    /** Restored runs dated later than now plus this are dropped (a future row would skew per-rule counters). */
    const val MAX_AUTOMATION_RUN_CLOCK_SKEW_MILLIS: Long = 24L * 60 * 60 * 1000

    /** Parts and addresses kept per imported MMS. */
    const val MAX_MMS_PARTS: Int = 256
    const val MAX_MMS_ADDRESSES: Int = 100

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    private val BLOB_ID = Regex("^[A-Za-z0-9_-]{1,128}$")

    /** True for a lower-case hex SHA-256, the only valid attachment entry name (never a path). */
    fun isSha256Hex(name: String): Boolean = name.length == 64 && SHA256_HEX.matches(name)

    /** True for a snapshot id usable in a blob name: no dots, slashes or other path syntax. */
    fun isSafeBlobId(id: String): Boolean = BLOB_ID.matches(id)
}

/**
 * Passes at most [limit] bytes through; reading more throws [ArchiveLimitException]. [onBytes] observes every
 * byte count read (used for archive-wide totals).
 */
class LimitedInputStream(
    input: InputStream,
    private val limit: Long,
    private val what: String,
    private val onBytes: (Long) -> Unit = {},
    private val closeUnderlying: Boolean = true,
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) record(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) record(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) record(skipped)
        return skipped
    }

    override fun markSupported(): Boolean = false

    override fun close() {
        if (closeUnderlying) super.close()
    }

    private fun record(n: Long) {
        count += n
        onBytes(n)
        if (count > limit) throw ArchiveLimitException("$what exceeds $limit bytes")
    }
}

/** Reads [input] fully, failing with [ArchiveLimitException] beyond [limit] bytes instead of exhausting memory. */
fun readBounded(input: InputStream, limit: Long, what: String): ByteArray =
    LimitedInputStream(input, limit, what).readBytes()

/**
 * Rejects JSON nested deeper than [maxDepth] before it reaches a recursive parser (a `[[[[…` file would otherwise
 * end in a StackOverflowError, which is not an Exception and would crash the app). Linear, string-aware scan.
 */
fun checkJsonDepth(text: CharSequence, maxDepth: Int = ArchiveLimits.MAX_JSON_DEPTH) {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '[', '{' -> if (++depth > maxDepth) throw ArchiveLimitException("JSON nested deeper than $maxDepth levels")
            ']', '}' -> if (depth > 0) depth--
        }
    }
}
