package app.dak.backup.crypto

import java.math.BigInteger
import java.security.SecureRandom

/**
 * A 24-character, human-typeable recovery code: 23 data characters (112 bits of random secret,
 * Crockford base32) plus one Crockford mod-37 check character, grouped in six blocks of four
 * (`XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`). Either the user's passphrase or this code unlocks a backup
 * (see [app.dak.backup.crypto.BackupCrypto]).
 */
object RecoveryCode {
    /** Number of raw secret bytes encoded (112 bits). */
    const val SECRET_BYTES = 14
    private const val DATA_CHARS = 23
    private const val TOTAL_CHARS = 24

    // Crockford's base32 alphabet: excludes I, L, O, U to avoid confusion with 1, 1, 0, V.
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    // Crockford's extended 37-symbol check-character alphabet (the 32 above plus 5 more), used only
    // for the trailing checksum character, per Crockford's own base32 specification.
    private const val CHECK_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ*~$=U"

    data class Generated(val secret: ByteArray, val formatted: String)

    /** Generates a fresh random recovery code. Show it to the user exactly once; it is not stored. */
    fun generate(random: SecureRandom = SecureRandom()): Generated {
        val secret = ByteArray(SECRET_BYTES)
        random.nextBytes(secret)
        return Generated(secret, format(secret))
    }

    /** Formats raw [secret] bytes (must be [SECRET_BYTES] long) into the grouped, checksummed display form. */
    fun format(secret: ByteArray): String {
        require(secret.size == SECRET_BYTES) { "recovery secret must be $SECRET_BYTES bytes" }
        val value = BigInteger(1, secret)
        val digits = CharArray(DATA_CHARS)
        var v = value
        val mask = BigInteger.valueOf(31)
        for (i in DATA_CHARS - 1 downTo 0) {
            digits[i] = ALPHABET[v.and(mask).toInt()]
            v = v.shiftRight(5)
        }
        val check = CHECK_ALPHABET[value.mod(BigInteger.valueOf(37)).toInt()]
        return (String(digits) + check).chunked(4).joinToString("-")
    }

    /**
     * Parses a user-typed recovery code, tolerating case, hyphens/spaces, and the common
     * look-alike substitutions (`I`/`L` -> `1`, `O` -> `0`). Returns the raw secret bytes, or a
     * failure describing why the code was rejected (wrong length or bad checksum) — never garbage.
     */
    fun parse(input: String): Result<ByteArray> {
        val cleaned = buildString {
            for (raw in input) {
                if (raw.isWhitespace() || raw == '-') continue
                append(normalize(raw.uppercaseChar()))
            }
        }
        if (cleaned.length != TOTAL_CHARS) {
            return Result.failure(InvalidRecoveryCodeException("Expected $TOTAL_CHARS characters, got ${cleaned.length}"))
        }
        var value = BigInteger.ZERO
        for (i in 0 until DATA_CHARS) {
            val idx = ALPHABET.indexOf(cleaned[i])
            if (idx < 0) return Result.failure(InvalidRecoveryCodeException("Invalid character '${cleaned[i]}'"))
            value = value.shiftLeft(5).or(BigInteger.valueOf(idx.toLong()))
        }
        val expectedCheckIdx = value.mod(BigInteger.valueOf(37)).toInt()
        val actualCheckChar = cleaned[DATA_CHARS]
        if (CHECK_ALPHABET.indexOf(actualCheckChar) != expectedCheckIdx) {
            return Result.failure(InvalidRecoveryCodeException("Checksum mismatch"))
        }
        return Result.success(fixedWidthBytes(value, SECRET_BYTES))
    }

    private fun normalize(c: Char): Char = when (c) {
        'I', 'L' -> '1'
        'O' -> '0'
        else -> c
    }

    private fun fixedWidthBytes(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray() // may have a leading 0x00 sign byte, or be shorter than `size`
        val out = ByteArray(size)
        val copyLen = minOf(size, raw.size)
        System.arraycopy(raw, raw.size - copyLen, out, size - copyLen, copyLen)
        return out
    }
}

class InvalidRecoveryCodeException(message: String) : Exception(message)
