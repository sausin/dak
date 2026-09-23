package app.dak.backup.crypto

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Base class for every typed failure this module can raise, so callers never see a raw crypto exception. */
sealed class BackupCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The passphrase did not unwrap the backup's data key. */
class WrongPassphraseException : BackupCryptoException("Wrong passphrase")

/** The recovery code's checksum was invalid, or it did not unwrap the backup's data key. */
class RecoveryCodeMismatchException(message: String) : BackupCryptoException(message)

/** The ciphertext failed authentication: it was truncated, corrupted, or tampered with. */
class TamperedException(message: String = "Backup data failed authentication (corrupted or tampered with)") :
    BackupCryptoException(message)

/** The header is missing, has the wrong magic, or is otherwise not a Dak-encrypted stream. */
class MalformedHeaderException(message: String) : BackupCryptoException(message)

/**
 * The end-to-end encryption envelope for backups. The user holds the key: a passphrase is
 * stretched with PBKDF2-HMAC-SHA256 into a wrapping key, which (together with a recovery code)
 * wraps a random AES-256 data key. The payload is encrypted in independently authenticated
 * segments (a STREAM construction) so decryption can start before the whole backup is downloaded,
 * while a final-segment flag still detects truncation.
 *
 * Binary layout written by [encryptingOutputStream]:
 * ```
 * magic            7 bytes   "DAKENC1"
 * version          1 byte
 * iterations       4 bytes   big-endian Int, PBKDF2 iteration count
 * saltLen          1 byte
 * salt             saltLen bytes
 * noncePrefixLen   1 byte
 * noncePrefix      noncePrefixLen bytes  (per-file random prefix for segment nonces)
 * pwWrappedLen     1 byte
 * pwWrapped        pwWrappedLen bytes    (12-byte GCM nonce + wrapped 32-byte data key + 16-byte tag)
 * recWrappedLen    1 byte
 * recWrapped       recWrappedLen bytes   (same shape, wrapped with the recovery-code key)
 * ---- ciphertext segments follow ----
 * segment: [4-byte length][ciphertext .. + 16-byte GCM tag]  (repeated until the final segment)
 * ```
 */
object BackupCrypto {
    const val MAGIC = "DAKENC1"
    private const val VERSION: Int = 1
    const val DEFAULT_ITERATIONS = 310_000

    /**
     * Accepted PBKDF2 iteration range. The count is read from the (unauthenticated) header, so an attacker-made
     * file could otherwise ask for 2^31 iterations (hours of CPU before the passphrase is even checked) or write
     * weakly protected backups; both ends are enforced on write and on read.
     */
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 5_000_000
    const val SALT_BYTES = 16
    const val SEGMENT_SIZE = 64 * 1024
    private const val NONCE_PREFIX_BYTES = 4
    private const val GCM_TAG_BITS = 128
    private const val GCM_NONCE_BYTES = 12
    private const val DATA_KEY_BYTES = 32
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val WRAPPED_KEY_BYTES = GCM_NONCE_BYTES + DATA_KEY_BYTES + GCM_TAG_BYTES
    private const val MIN_SALT_BYTES = 16
    private const val MAX_SALT_BYTES = 64

    /** Largest ciphertext segment a writer produces; anything larger is refused before allocating. */
    private const val MAX_SEGMENT_CIPHERTEXT = SEGMENT_SIZE + GCM_TAG_BYTES

    data class EncryptResult(val recoveryCode: RecoveryCode.Generated, val output: OutputStream)

    private data class Header(
        val iterations: Int,
        val salt: ByteArray,
        val noncePrefix: ByteArray,
        val pwWrapped: ByteArray,
        val recWrapped: ByteArray,
    )

    /**
     * Starts encrypting a backup into [rawOutput]. Writes the header immediately and returns an
     * [OutputStream]: write the plaintext export to it and [OutputStream.close] it to flush the
     * final authenticated segment. Also returns a freshly generated [RecoveryCode.Generated] —
     * show it to the user once; it is the only way back into the backup if the passphrase is lost.
     */
    fun encryptingOutputStream(
        rawOutput: OutputStream,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): EncryptResult {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations must be in $MIN_ITERATIONS..$MAX_ITERATIONS" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
        val dataKeyBytes = ByteArray(DATA_KEY_BYTES).also(random::nextBytes)
        val dataKey = SecretKeySpec(dataKeyBytes, "AES")

        val passphraseKey = deriveKey(passphrase, salt, iterations)
        val recovery = RecoveryCode.generate(random)
        val recoveryKey = deriveRecoveryKey(recovery.secret)

        val pwWrapped = wrap(dataKey, passphraseKey, random)
        val recWrapped = wrap(dataKey, recoveryKey, random)

        val out = DataOutputStream(rawOutput)
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.writeByte(VERSION)
        out.writeInt(iterations)
        writeLenPrefixed(out, salt)
        writeLenPrefixed(out, noncePrefix)
        writeLenPrefixed(out, pwWrapped)
        writeLenPrefixed(out, recWrapped)
        out.flush()

        return EncryptResult(recovery, StreamEncryptingOutputStream(rawOutput, dataKey, noncePrefix, random))
    }

    /** Decrypts a backup written by [encryptingOutputStream] using the original [passphrase]. */
    fun decryptingInputStream(rawInput: InputStream, passphrase: CharArray): InputStream {
        val header = readHeader(rawInput)
        val passphraseKey = deriveKey(passphrase, header.salt, header.iterations)
        val dataKey = try {
            unwrap(header.pwWrapped, passphraseKey)
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseException()
        }
        return StreamDecryptingInputStream(rawInput, dataKey, header.noncePrefix)
    }

    /** Decrypts a backup written by [encryptingOutputStream] using the recovery code instead of the passphrase. */
    fun decryptingInputStreamWithRecoveryCode(rawInput: InputStream, recoveryCode: String): InputStream {
        val header = readHeader(rawInput)
        val secret = RecoveryCode.parse(recoveryCode).getOrElse {
            throw RecoveryCodeMismatchException(it.message ?: "Invalid recovery code")
        }
        val recoveryKey = deriveRecoveryKey(secret)
        val dataKey = try {
            unwrap(header.recWrapped, recoveryKey)
        } catch (e: AEADBadTagException) {
            throw RecoveryCodeMismatchException("Recovery code did not unlock this backup")
        }
        return StreamDecryptingInputStream(rawInput, dataKey, header.noncePrefix)
    }

    private fun readHeader(rawInput: InputStream): Header {
        val input = DataInputStream(rawInput)
        val magic = ByteArray(MAGIC.length)
        try {
            input.readFully(magic)
        } catch (e: EOFException) {
            throw MalformedHeaderException("Stream is too short to contain a Dak encryption header")
        }
        if (String(magic, Charsets.US_ASCII) != MAGIC) throw MalformedHeaderException("Bad magic: not a Dak-encrypted backup")
        val version = try {
            input.readUnsignedByte()
        } catch (e: EOFException) {
            throw MalformedHeaderException("Header truncated")
        }
        if (version != VERSION) throw MalformedHeaderException("Unsupported encryption header version $version")
        val iterations = try {
            input.readInt()
        } catch (e: EOFException) {
            throw MalformedHeaderException("Header truncated")
        }
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw MalformedHeaderException("Unsupported iteration count $iterations")
        val salt = readLenPrefixed(input)
        val noncePrefix = readLenPrefixed(input)
        val pwWrapped = readLenPrefixed(input)
        val recWrapped = readLenPrefixed(input)
        if (salt.size !in MIN_SALT_BYTES..MAX_SALT_BYTES) throw MalformedHeaderException("Invalid salt length ${salt.size}")
        if (noncePrefix.size != NONCE_PREFIX_BYTES) throw MalformedHeaderException("Invalid nonce prefix length ${noncePrefix.size}")
        if (pwWrapped.size != WRAPPED_KEY_BYTES || recWrapped.size != WRAPPED_KEY_BYTES) {
            throw MalformedHeaderException("Invalid wrapped key length")
        }
        return Header(iterations, salt, noncePrefix, pwWrapped, recWrapped)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, iterations, DATA_KEY_BYTES * 8)
        val derived = try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return SecretKeySpec(derived, "AES")
    }

    /** The recovery secret is already uniformly random and single-use, so a plain hash is sufficient as a KDF. */
    private fun deriveRecoveryKey(secret: ByteArray): SecretKey =
        SecretKeySpec(java.security.MessageDigest.getInstance("SHA-256").digest(secret), "AES")

    private fun wrap(dataKey: SecretKey, wrappingKey: SecretKey, random: SecureRandom): ByteArray {
        val nonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ct = cipher.doFinal(dataKey.encoded)
        return nonce + ct
    }

    private fun unwrap(wrapped: ByteArray, wrappingKey: SecretKey): SecretKey {
        require(wrapped.size > GCM_NONCE_BYTES) { "wrapped key too short" }
        val nonce = wrapped.copyOfRange(0, GCM_NONCE_BYTES)
        val ct = wrapped.copyOfRange(GCM_NONCE_BYTES, wrapped.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val keyBytes = cipher.doFinal(ct)
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun writeLenPrefixed(out: DataOutputStream, bytes: ByteArray) {
        require(bytes.size < 256)
        out.writeByte(bytes.size)
        out.write(bytes)
    }

    private fun readLenPrefixed(input: DataInputStream): ByteArray = try {
        val bytes = ByteArray(input.readUnsignedByte())
        input.readFully(bytes)
        bytes
    } catch (e: EOFException) {
        throw MalformedHeaderException("Header truncated")
    }

    /** 12-byte GCM nonce for one segment: 4-byte file prefix + 4-byte big-endian counter + 3 zero bytes + final flag. */
    private fun segmentNonce(prefix: ByteArray, counter: Int, isFinal: Boolean): ByteArray {
        val nonce = ByteArray(GCM_NONCE_BYTES)
        System.arraycopy(prefix, 0, nonce, 0, prefix.size)
        nonce[prefix.size] = (counter ushr 24).toByte()
        nonce[prefix.size + 1] = (counter ushr 16).toByte()
        nonce[prefix.size + 2] = (counter ushr 8).toByte()
        nonce[prefix.size + 3] = counter.toByte()
        nonce[GCM_NONCE_BYTES - 1] = if (isFinal) 1 else 0
        return nonce
    }

    /** Buffers up to [SEGMENT_SIZE] plaintext bytes, then AES-256-GCM-encrypts and frames each segment. */
    private class StreamEncryptingOutputStream(
        rawOut: OutputStream,
        private val key: SecretKey,
        private val prefix: ByteArray,
        private val random: SecureRandom,
    ) : FilterOutputStream(DataOutputStream(rawOut)) {
        private val buffer = ByteArray(SEGMENT_SIZE)
        private var filled = 0
        private var counter = 0
        private var closed = false

        override fun write(b: Int) {
            buffer[filled++] = b.toByte()
            if (filled == buffer.size) flushSegment(isFinal = false)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var remaining = len
            while (remaining > 0) {
                val space = buffer.size - filled
                val n = minOf(space, remaining)
                System.arraycopy(b, o, buffer, filled, n)
                filled += n
                o += n
                remaining -= n
                if (filled == buffer.size) flushSegment(isFinal = false)
            }
        }

        override fun flush() {
            // Intermediate flush() calls do not force a segment boundary: GCM segments are sized by
            // SEGMENT_SIZE, not by caller write() granularity. Only close() emits the final segment.
        }

        /** Flushes the final authenticated segment, then closes the underlying [OutputStream]. */
        override fun close() {
            if (closed) return
            flushSegment(isFinal = true)
            out.flush()
            out.close()
            closed = true
        }

        private fun flushSegment(isFinal: Boolean) {
            val nonce = segmentNonce(prefix, counter, isFinal)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val ct = cipher.doFinal(buffer, 0, filled)
            (out as DataOutputStream).writeInt(ct.size)
            out.write(ct)
            counter++
            filled = 0
        }
    }

    /**
     * Reads length-framed segments and decrypts each with the STREAM nonce construction. Whether a
     * segment is the final one is not known until the *next* segment's length header is peeked: if
     * none follows, the current segment is decrypted as final. A stream truncated right after a
     * non-final segment therefore fails authentication here (the true last segment was sealed with
     * `isFinal=false`, but decryption at genuine end-of-stream is attempted with `isFinal=true`),
     * which is exactly how truncation is detected without a separate checksum.
     */
    private class StreamDecryptingInputStream(
        rawIn: InputStream,
        private val key: SecretKey,
        private val prefix: ByteArray,
    ) : InputStream() {
        private val input = DataInputStream(rawIn)
        private var counter = 0
        private var currentSegment: ByteArray? = null
        private var currentPos = 0
        private var finished = false
        private var pendingLengthBytes: ByteArray? = null

        override fun read(): Int {
            val seg = ensureSegment() ?: return -1
            val v = seg[currentPos].toInt() and 0xFF
            currentPos++
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val seg = ensureSegment() ?: return -1
            val n = minOf(len, seg.size - currentPos)
            System.arraycopy(seg, currentPos, b, off, n)
            currentPos += n
            return n
        }

        private fun ensureSegment(): ByteArray? {
            val seg = currentSegment
            if (seg != null && currentPos < seg.size) return seg
            if (finished) return null
            return readNextSegment()
        }

        private fun readNextSegment(): ByteArray? {
            val lengthBytes = tryReadFully(4) ?: run { finished = true; return null }
            if (lengthBytes.size != 4) throw TamperedException("Truncated segment length header")
            val ctLen = ((lengthBytes[0].toInt() and 0xFF) shl 24) or ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                ((lengthBytes[2].toInt() and 0xFF) shl 8) or (lengthBytes[3].toInt() and 0xFF)
            if (ctLen < GCM_TAG_BYTES || ctLen > MAX_SEGMENT_CIPHERTEXT) throw TamperedException("Implausible segment length $ctLen")
            val ct = ByteArray(ctLen)
            try {
                input.readFully(ct)
            } catch (e: EOFException) {
                throw TamperedException("Truncated segment body")
            }
            val isFinal = peekIsAtEnd()
            val nonce = segmentNonce(prefix, counter, isFinal)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val pt = try {
                cipher.doFinal(ct)
            } catch (e: AEADBadTagException) {
                throw TamperedException()
            }
            counter++
            if (isFinal) finished = true
            currentSegment = pt
            currentPos = 0
            return if (pt.isEmpty()) (if (finished) null else readNextSegment()) else pt
        }

        /** Peeks for a following segment without consuming it; true if the underlying stream is at EOF. */
        private fun peekIsAtEnd(): Boolean {
            val peeked = pendingLengthBytes
            if (peeked != null) return false // we already know there is more
            val next = tryReadFully(4)
            return if (next == null) {
                true
            } else {
                pendingLengthBytes = next
                false
            }
        }

        private fun tryReadFully(n: Int): ByteArray? {
            pendingLengthBytes?.let {
                pendingLengthBytes = null
                return it
            }
            val buf = ByteArray(n)
            var total = 0
            while (total < n) {
                val r = input.read(buf, total, n - total)
                if (r < 0) break
                total += r
            }
            if (total == 0) return null
            if (total < n) throw TamperedException("Truncated stream")
            return buf
        }

        override fun close() {
            input.close()
        }
    }
}
