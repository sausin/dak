package app.dak.backup.format

import java.io.InputStream
import java.security.MessageDigest

/** Small SHA-256 helpers shared by the export format, crypto envelope and the incremental planner. */
object Hashing {
    private const val ALGO = "SHA-256"

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance(ALGO).digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = sha256(bytes).toHex()

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /** Consumes [input] fully, closing it, and returns the lower-case hex SHA-256 digest of its bytes. */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance(ALGO)
        val buffer = ByteArray(8 * 1024)
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        val hex = "0123456789abcdef"
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            chars[i * 2] = hex[v ushr 4]
            chars[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(chars)
    }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd-length hex string" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex string" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
