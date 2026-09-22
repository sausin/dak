package app.dak.backup.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupCryptoTest {

    // Low iteration count so the test suite runs fast; production uses BackupCrypto.DEFAULT_ITERATIONS.
    private val testIterations = 100

    private fun encrypt(plaintext: ByteArray, passphrase: CharArray): Pair<ByteArray, String> {
        val out = ByteArrayOutputStream()
        val result = BackupCrypto.encryptingOutputStream(out, passphrase, testIterations)
        result.output.use { it.write(plaintext) }
        return out.toByteArray() to result.recoveryCode.formatted
    }

    @Test
    fun `round trips small and large payloads with the passphrase`() {
        for (size in listOf(0, 1, 100, BackupCrypto.SEGMENT_SIZE, BackupCrypto.SEGMENT_SIZE + 1, BackupCrypto.SEGMENT_SIZE * 3 + 17)) {
            val plaintext = ByteArray(size) { (it % 251).toByte() }
            val (ciphertext, _) = encrypt(plaintext, "correct horse battery staple".toCharArray())
            val decrypted = BackupCrypto.decryptingInputStream(ByteArrayInputStream(ciphertext), "correct horse battery staple".toCharArray()).use { it.readBytes() }
            assertContentEquals(plaintext, decrypted)
        }
    }

    @Test
    fun `recovery code unlocks the same payload`() {
        val plaintext = "hello from the recovery path".toByteArray()
        val (ciphertext, recoveryCode) = encrypt(plaintext, "my passphrase".toCharArray())
        val decrypted = BackupCrypto.decryptingInputStreamWithRecoveryCode(ByteArrayInputStream(ciphertext), recoveryCode).use { it.readBytes() }
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `wrong passphrase raises a typed error, not garbage`() {
        val (ciphertext, _) = encrypt("secret data".toByteArray(), "right passphrase".toCharArray())
        assertFailsWith<WrongPassphraseException> {
            BackupCrypto.decryptingInputStream(ByteArrayInputStream(ciphertext), "wrong passphrase".toCharArray()).readBytes()
        }
    }

    @Test
    fun `wrong recovery code raises a typed error`() {
        val (ciphertext, _) = encrypt("secret data".toByteArray(), "a passphrase".toCharArray())
        val other = RecoveryCode.generate().formatted
        assertFailsWith<RecoveryCodeMismatchException> {
            BackupCrypto.decryptingInputStreamWithRecoveryCode(ByteArrayInputStream(ciphertext), other).readBytes()
        }
    }

    @Test
    fun `malformed recovery code string is rejected before touching crypto`() {
        val (ciphertext, _) = encrypt("secret data".toByteArray(), "a passphrase".toCharArray())
        assertFailsWith<RecoveryCodeMismatchException> {
            BackupCrypto.decryptingInputStreamWithRecoveryCode(ByteArrayInputStream(ciphertext), "not-a-real-code")
        }
    }

    @Test
    fun `a single flipped bit in the ciphertext is detected`() {
        val (ciphertext, _) = encrypt(ByteArray(BackupCrypto.SEGMENT_SIZE + 500) { it.toByte() }, "pw".toCharArray())
        val tampered = ciphertext.copyOf()
        tampered[tampered.size - 5] = (tampered[tampered.size - 5].toInt() xor 0x01).toByte()
        assertFailsWith<TamperedException> {
            BackupCrypto.decryptingInputStream(ByteArrayInputStream(tampered), "pw".toCharArray()).readBytes()
        }
    }

    @Test
    fun `truncation is detected even though the truncated bytes were validly encrypted`() {
        val (ciphertext, _) = encrypt(ByteArray(BackupCrypto.SEGMENT_SIZE * 2 + 100) { it.toByte() }, "pw".toCharArray())
        // Cut off after the first full segment, before the final (flagged) segment arrives.
        val truncated = ciphertext.copyOf(ciphertext.size - BackupCrypto.SEGMENT_SIZE)
        assertFailsWith<TamperedException> {
            BackupCrypto.decryptingInputStream(ByteArrayInputStream(truncated), "pw".toCharArray()).readBytes()
        }
    }

    @Test
    fun `truncation at an exact segment boundary is still detected via the final-segment flag`() {
        // Exactly one full segment's worth of plaintext: segment 0 is flushed non-final while
        // writing, then close() flushes an empty *final* segment. Removing that trailing empty
        // final segment leaves segment 0 (encrypted non-final) looking like a clean end of stream.
        val (ciphertext, _) = encrypt(ByteArray(BackupCrypto.SEGMENT_SIZE) { it.toByte() }, "pw".toCharArray())
        val emptyFinalSegmentFramedSize = 4 + 16 // 4-byte length prefix + a 0-byte-plaintext GCM tag
        val truncated = ciphertext.copyOf(ciphertext.size - emptyFinalSegmentFramedSize)
        assertFailsWith<TamperedException> {
            BackupCrypto.decryptingInputStream(ByteArrayInputStream(truncated), "pw".toCharArray()).readBytes()
        }
    }

    @Test
    fun `bad magic is rejected as malformed, not a crash`() {
        val bogus = ByteArrayInputStream("not a dak backup at all".toByteArray())
        assertFailsWith<MalformedHeaderException> {
            BackupCrypto.decryptingInputStream(bogus, "pw".toCharArray())
        }
    }

    @Test
    fun `each encryption uses a fresh recovery code and salt`() {
        val out1 = ByteArrayOutputStream()
        val r1 = BackupCrypto.encryptingOutputStream(out1, "pw".toCharArray(), testIterations)
        r1.output.close()
        val out2 = ByteArrayOutputStream()
        val r2 = BackupCrypto.encryptingOutputStream(out2, "pw".toCharArray(), testIterations)
        r2.output.close()
        assertTrue(r1.recoveryCode.formatted != r2.recoveryCode.formatted)
        assertTrue(!out1.toByteArray().contentEquals(out2.toByteArray()))
    }
}
