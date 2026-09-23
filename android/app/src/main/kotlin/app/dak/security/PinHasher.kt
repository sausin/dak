package app.dak.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Shape rules for the app-specific PIN: 4 to 8 ASCII digits. */
object PinPolicy {
    const val MIN_LENGTH: Int = 4
    const val MAX_LENGTH: Int = 8

    /** True when [pin] is 4–8 ASCII digits (no other characters, no whitespace). */
    fun isValid(pin: CharArray): Boolean =
        pin.size in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }
}

/**
 * A salted PBKDF2 hash of the app PIN — the only thing ever persisted (the PIN itself never is).
 *
 * Encoded as `v1:<iterations>:<base64 salt>:<base64 hash>`; [iterations] is stored so the cost can be raised later
 * without invalidating existing PINs. Not a data class on purpose: array fields must not get structural equality or a
 * `toString` that prints them.
 */
class PinRecord(val iterations: Int, salt: ByteArray, hash: ByteArray) {
    private val saltBytes = salt.copyOf()
    private val hashBytes = hash.copyOf()

    val salt: ByteArray get() = saltBytes.copyOf()
    val hash: ByteArray get() = hashBytes.copyOf()

    fun encode(): String {
        val b64 = Base64.getEncoder()
        return "$VERSION:$iterations:${b64.encodeToString(saltBytes)}:${b64.encodeToString(hashBytes)}"
    }

    override fun toString(): String = "PinRecord(iterations=$iterations)"

    companion object {
        private const val VERSION = "v1"

        /** Refuses absurd costs from a tampered file (verification would otherwise hang). */
        private const val MAX_DECODED_ITERATIONS = 10_000_000

        /** Parses [encoded]; null when it is not a well-formed v1 record. */
        fun decode(encoded: String?): PinRecord? {
            val parts = encoded?.trim()?.split(':') ?: return null
            if (parts.size != 4 || parts[0] != VERSION) return null
            val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..MAX_DECODED_ITERATIONS } ?: return null
            return runCatching {
                val b64 = Base64.getDecoder()
                val salt = b64.decode(parts[2])
                val hash = b64.decode(parts[3])
                if (salt.isEmpty() || hash.isEmpty()) null else PinRecord(iterations, salt, hash)
            }.getOrNull()
        }
    }
}

/**
 * Hashes and verifies the app PIN with PBKDF2WithHmacSHA256 (available on Android 8.0+ and every JVM), a random
 * 16-byte salt and [iterations] rounds (at least [MIN_ITERATIONS]). Verification uses a constant-time comparison.
 * Both operations are CPU-bound (hundreds of milliseconds on a slow phone): call them off the main thread.
 */
class PinHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(iterations >= MIN_ITERATIONS) { "PBKDF2 iterations must be at least $MIN_ITERATIONS" }
    }

    /** Hashes a valid [pin] with a fresh salt. The caller should wipe [pin] afterwards. */
    fun hash(pin: CharArray): PinRecord {
        require(PinPolicy.isValid(pin)) { "PIN must be ${PinPolicy.MIN_LENGTH}-${PinPolicy.MAX_LENGTH} digits" }
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        return PinRecord(iterations, salt, derive(pin, salt, iterations))
    }

    /** True when [pin] matches [record]. Malformed input never matches. */
    fun verify(pin: CharArray, record: PinRecord): Boolean {
        if (!PinPolicy.isValid(pin)) return false
        val expected = record.hash
        val actual = derive(pin, record.salt, record.iterations, expected.size * 8)
        return constantTimeEquals(expected, actual)
    }

    companion object {
        /** OWASP-style floor for PBKDF2-HMAC-SHA256 on a short numeric secret stored off-backup. */
        const val MIN_ITERATIONS: Int = 100_000
        const val DEFAULT_ITERATIONS: Int = 120_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256

        private fun derive(pin: CharArray, salt: ByteArray, iterations: Int, bits: Int = KEY_BITS): ByteArray {
            val spec = PBEKeySpec(pin, salt, iterations, bits)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        /** Compares every byte regardless of where the first difference is (no early exit). */
        fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
            var diff = a.size xor b.size
            val n = minOf(a.size, b.size)
            for (i in 0 until n) diff = diff or (a[i].toInt() xor b[i].toInt())
            return diff == 0
        }
    }
}
