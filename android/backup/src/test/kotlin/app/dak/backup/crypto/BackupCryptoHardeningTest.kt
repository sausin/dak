package app.dak.backup.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Attacks on the encrypted container beyond single bit flips: reordering, dropping and duplicating segments, header
 * edits, trailing bytes; plus a golden ciphertext written by v1 that every future build must still open.
 */
class BackupCryptoHardeningTest {

    private val pw = "correct horse battery staple"
    private val iterations = BackupCrypto.MIN_ITERATIONS

    /** magic(7) + version(1) + iterations(4) + salt(1+16) + prefix(1+4) + pwWrapped(1+60) + recWrapped(1+60). */
    private val headerLen = 7 + 1 + 4 + 17 + 5 + 61 + 61

    private fun encrypt(plain: ByteArray, passphrase: String = pw): Pair<ByteArray, String> {
        val out = ByteArrayOutputStream()
        val r = BackupCrypto.encryptingOutputStream(out, passphrase.toCharArray(), iterations)
        r.output.use { it.write(plain) }
        return out.toByteArray() to r.recoveryCode.formatted
    }

    private fun decrypt(bytes: ByteArray, passphrase: String = pw): ByteArray =
        BackupCrypto.decryptingInputStream(ByteArrayInputStream(bytes), passphrase.toCharArray()).use { it.readBytes() }

    /** Splits the ciphertext body into its framed segments (length prefix included). */
    private fun segments(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var p = headerLen
        while (p < bytes.size) {
            val len = ByteBuffer.wrap(bytes, p, 4).int
            out += bytes.copyOfRange(p, p + 4 + len)
            p += 4 + len
        }
        return out
    }

    private fun reassemble(bytes: ByteArray, segs: List<ByteArray>): ByteArray =
        segs.fold(bytes.copyOf(headerLen)) { acc, s -> acc + s }

    private val threeSegments = ByteArray(BackupCrypto.SEGMENT_SIZE * 2 + 100) { (it * 7 % 256).toByte() }

    @Test
    fun `layout has the documented header and one frame per segment`() {
        val (ct, _) = encrypt(threeSegments)
        assertEquals("DAKENC1", String(ct.copyOf(7), Charsets.US_ASCII))
        assertEquals(1, ct[7].toInt())
        assertEquals(iterations, ByteBuffer.wrap(ct, 8, 4).int)
        val segs = segments(ct)
        assertEquals(3, segs.size)
        assertEquals(listOf(BackupCrypto.SEGMENT_SIZE + 16, BackupCrypto.SEGMENT_SIZE + 16, 100 + 16), segs.map { it.size - 4 })
    }

    @Test
    fun `swapping, dropping or duplicating segments is detected`() {
        val (ct, _) = encrypt(threeSegments)
        val (a, b, c) = segments(ct)
        for ((name, forged) in listOf(
            "swap first two" to listOf(b, a, c),
            "drop middle" to listOf(a, c),
            "drop last" to listOf(a, b),
            "duplicate first" to listOf(a, a, b, c),
            "final moved first" to listOf(c, a, b),
            "final duplicated" to listOf(a, b, c, c),
        )) {
            assertFailsWith<TamperedException>(name) { decrypt(reassemble(ct, forged)) }
        }
        assertContentEquals(threeSegments, decrypt(reassemble(ct, listOf(a, b, c))))
    }

    @Test
    fun `segments cannot be spliced between two backups made with the same passphrase`() {
        val (ct1, _) = encrypt(threeSegments)
        val (ct2, _) = encrypt(threeSegments)
        val s1 = segments(ct1)
        val s2 = segments(ct2)
        assertFailsWith<TamperedException> { decrypt(reassemble(ct1, listOf(s1[0], s2[1], s1[2]))) }
    }

    @Test
    fun `trailing bytes after the final segment are detected`() {
        val (ct, _) = encrypt("short".toByteArray())
        for (extra in 1..6) {
            assertFailsWith<TamperedException>("extra $extra") { decrypt(ct + ByteArray(extra)) }
        }
    }

    @Test
    fun `truncating anywhere inside the body is detected`() {
        val (ct, _) = encrypt(ByteArray(BackupCrypto.SEGMENT_SIZE + 50) { 1 })
        val cuts = listOf(headerLen + 1, headerLen + 3, headerLen + 4, headerLen + 100, ct.size - 17, ct.size - 1) +
            // exactly at the boundary between the two frames
            listOf(headerLen + 4 + BackupCrypto.SEGMENT_SIZE + 16)
        for (cut in cuts) assertFailsWith<TamperedException>("cut at $cut") { decrypt(ct.copyOf(cut)) }
    }

    @Test
    fun `a header with no body is truncation, not an empty backup`() {
        // Every backup has at least the final segment (an empty payload still gets one), so a body cut off right
        // after the header must fail authentication rather than read as "no messages".
        val (ct, _) = encrypt(ByteArray(0))
        assertEquals(0, decrypt(ct).size)
        assertEquals(headerLen + 4 + 16, ct.size, "empty payload = one empty final segment")
        assertFailsWith<TamperedException> { decrypt(ct.copyOf(headerLen)) }
    }

    @Test
    fun `editing the unauthenticated header fails safely`() {
        val (ct, _) = encrypt("secret".toByteArray())
        // Nonce prefix (bytes 30..33): every segment's nonce changes, so authentication fails.
        val prefix = ct.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }
        assertFailsWith<TamperedException> { decrypt(prefix) }
        // Salt: a different derived key, so the passphrase no longer unwraps the data key.
        val salt = ct.copyOf().also { it[14] = (it[14].toInt() xor 1).toByte() }
        assertFailsWith<WrongPassphraseException> { decrypt(salt) }
        // Iteration count raised within the accepted range: also a different key.
        val iters = ct.copyOf().also { ByteBuffer.wrap(it, 8, 4).putInt(iterations + 1) }
        assertFailsWith<WrongPassphraseException> { decrypt(iters) }
        // Version byte.
        val version = ct.copyOf().also { it[7] = 2 }
        assertFailsWith<MalformedHeaderException> { decrypt(version) }
    }

    @Test
    fun `the passphrase and recovery slots cannot be swapped`() {
        val (ct, code) = encrypt("secret".toByteArray())
        val pwStart = 7 + 1 + 4 + 17 + 5 + 1
        val recStart = pwStart + 60 + 1
        val swapped = ct.copyOf()
        System.arraycopy(ct, recStart, swapped, pwStart, 60)
        System.arraycopy(ct, pwStart, swapped, recStart, 60)
        assertFailsWith<WrongPassphraseException> { decrypt(swapped) }
        assertFailsWith<RecoveryCodeMismatchException> {
            BackupCrypto.decryptingInputStreamWithRecoveryCode(ByteArrayInputStream(swapped), code).readBytes()
        }
    }

    @Test
    fun `a valid recovery code for another backup is refused with a typed error`() {
        val (ct, _) = encrypt("secret".toByteArray())
        val other = RecoveryCode.generate(SecureRandom()).formatted
        assertFailsWith<RecoveryCodeMismatchException> {
            BackupCrypto.decryptingInputStreamWithRecoveryCode(ByteArrayInputStream(ct), other)
        }
    }

    @Test
    fun `passphrases are exact - case, whitespace and unicode normalisation matter`() {
        val (ct, _) = encrypt("x".toByteArray(), passphrase = "Café pass")
        assertContentEquals("x".toByteArray(), decrypt(ct, "Café pass"))
        assertFailsWith<WrongPassphraseException> { decrypt(ct, "café pass") }
        assertFailsWith<WrongPassphraseException> { decrypt(ct, "Café pass ") }
        // NFD "e + combining acute" is a different passphrase to the KDF: callers must normalise before calling.
        assertFailsWith<WrongPassphraseException> { decrypt(ct, "Café pass") }
    }

    @Test
    fun `an empty passphrase still round trips (the UI enforces strength, not the container)`() {
        val (ct, _) = encrypt("x".toByteArray(), passphrase = "")
        assertContentEquals("x".toByteArray(), decrypt(ct, ""))
    }

    @Test
    fun `the stream can be read byte by byte and returns -1 forever at the end`() {
        val plain = ByteArray(BackupCrypto.SEGMENT_SIZE + 3) { it.toByte() }
        val (ct, _) = encrypt(plain)
        val input = BackupCrypto.decryptingInputStream(ByteArrayInputStream(ct), pw.toCharArray())
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
        }
        assertContentEquals(plain, out.toByteArray())
        assertEquals(-1, input.read())
        assertEquals(-1, input.read(ByteArray(8), 0, 8))
        assertEquals(0, input.read(ByteArray(8), 0, 0))
    }

    @Test
    fun `writes of every granularity produce the same plaintext`() {
        val plain = ByteArray(BackupCrypto.SEGMENT_SIZE * 2 + 5) { (it % 13).toByte() }
        val out = ByteArrayOutputStream()
        val r = BackupCrypto.encryptingOutputStream(out, pw.toCharArray(), iterations)
        r.output.use { o ->
            var i = 0
            var step = 1
            while (i < plain.size) {
                val n = minOf(step, plain.size - i)
                if (n == 1) o.write(plain[i].toInt()) else o.write(plain, i, n)
                i += n
                step = step * 3 % 70_001 + 1
                o.flush() // must not force a segment boundary
            }
        }
        assertEquals(3, segments(out.toByteArray()).size, "flush() must not create extra segments")
        assertContentEquals(plain, decrypt(out.toByteArray()))
    }

    @Test
    fun `closing twice is harmless`() {
        val out = ByteArrayOutputStream()
        val r = BackupCrypto.encryptingOutputStream(out, pw.toCharArray(), iterations)
        r.output.write(1)
        r.output.close()
        val size = out.size()
        r.output.close()
        assertEquals(size, out.size())
    }

    // --- golden: a v1 backup must open forever -----------------------------------------------------------------

    private val goldenPlain = "Dak golden plaintext v1\nSecond line, with unicode: दाक ✓\n"
    private val goldenRecovery = "0P43-CVQ1-WR12-H0DZ-FY5W-X4ZS"

    private fun golden(): ByteArray = Base64.getMimeDecoder().decode(
        requireNotNull(javaClass.getResource("/golden/encrypted-v1.b64")).readText(),
    )

    @Test
    fun `a backup written by format v1 still decrypts with its passphrase`() {
        assertEquals(goldenPlain, decrypt(golden()).toString(Charsets.UTF_8))
    }

    @Test
    fun `a backup written by format v1 still decrypts with its recovery code however it is typed`() {
        for (typed in listOf(goldenRecovery, goldenRecovery.lowercase(), goldenRecovery.replace("-", " "), goldenRecovery.replace("0", "O"))) {
            val plain = BackupCrypto.decryptingInputStreamWithRecoveryCode(ByteArrayInputStream(golden()), typed).use { it.readBytes() }
            assertEquals(goldenPlain, plain.toString(Charsets.UTF_8), typed)
        }
    }

    // --- recovery code format ------------------------------------------------------------------------------------

    @Test
    fun `recovery code formatting is pinned`() {
        assertEquals("0002-0G30-G2GC-1R81-450P-30DG", RecoveryCode.format(ByteArray(RecoveryCode.SECRET_BYTES) { it.toByte() }))
        assertEquals("3ZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZF", RecoveryCode.format(ByteArray(RecoveryCode.SECRET_BYTES) { -1 }))
        assertEquals("0000-0000-0000-0000-0000-0000", RecoveryCode.format(ByteArray(RecoveryCode.SECRET_BYTES)))
        assertFailsWith<IllegalArgumentException> { RecoveryCode.format(ByteArray(13)) }
    }

    @Test
    fun `recovery codes round trip for edge secrets, including leading zero and high bytes`() {
        val secrets = listOf(
            ByteArray(14), ByteArray(14) { -1 }, ByteArray(14).also { it[13] = 1 }, ByteArray(14).also { it[0] = -128 },
        ) + List(50) { i -> ByteArray(14).also { java.util.Random(i.toLong()).nextBytes(it) } }
        for (s in secrets) {
            val parsed = RecoveryCode.parse(RecoveryCode.format(s)).getOrThrow()
            assertContentEquals(s, parsed)
        }
    }

    @Test
    fun `every single-character typo is caught by the checksum`() {
        val code = RecoveryCode.format(ByteArray(14) { (it * 17).toByte() }).replace("-", "")
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var checked = 0
        for (i in 0 until 23) for (c in alphabet) {
            if (c == code[i]) continue
            val typo = code.substring(0, i) + c + code.substring(i + 1)
            assertTrue(RecoveryCode.parse(typo).isFailure, "typo at $i -> $c accepted")
            checked++
        }
        assertEquals(23 * 31, checked)
    }

    @Test
    fun `a code encoding more than 112 bits never yields a silently truncated secret`() {
        // 23 base32 characters carry 115 bits; a code whose top bits are set cannot be one we generated.
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val checkAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ*~$=U"
        val value = BigInteger.ONE.shiftLeft(114).add(BigInteger.valueOf(12345))
        val chars = CharArray(23)
        var v = value
        for (i in 22 downTo 0) { chars[i] = alphabet[v.and(BigInteger.valueOf(31)).toInt()]; v = v.shiftRight(5) }
        val code = String(chars) + checkAlphabet[value.mod(BigInteger.valueOf(37)).toInt()]
        val (ct, _) = encrypt("secret".toByteArray())
        val e = runCatching {
            BackupCrypto.decryptingInputStreamWithRecoveryCode(ByteArrayInputStream(ct), code).readBytes()
        }.exceptionOrNull()
        assertIs<RecoveryCodeMismatchException>(e)
    }
}
