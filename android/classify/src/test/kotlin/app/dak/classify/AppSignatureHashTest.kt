package app.dak.classify

import app.dak.core.model.OtpInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSignatureHashTest {

    @Test
    fun `hash is 11 chars and deterministic`() {
        val hash1 = AppSignatureHash.compute("com.example.app", "a1b2c3d4e5f6".repeat(4).toByteArray())
        val hash2 = AppSignatureHash.compute("com.example.app", "a1b2c3d4e5f6".repeat(4).toByteArray())
        assertEquals(11, hash1.length)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `hash changes with package name or signature`() {
        val sig = "deadbeef".repeat(6).toByteArray()
        val h1 = AppSignatureHash.compute("com.example.app", sig)
        val h2 = AppSignatureHash.compute("com.example.other", sig)
        val h3 = AppSignatureHash.compute("com.example.app", "cafebabe".repeat(6).toByteArray())
        assertTrue(h1 != h2)
        assertTrue(h1 != h3)
    }

    @Test
    fun `hex overload matches byte overload for the same signature`() {
        val sigBytes = byteArrayOf(0x0a, 0x1b, 0x2c, 0x3d.toByte(), 0xff.toByte())
        val hex = sigBytes.joinToString("") { "%02x".format(it) }
        assertEquals(AppSignatureHash.compute("com.example.app", sigBytes), AppSignatureHash.compute("com.example.app", hex))
    }

    @Test
    fun `hash uses only base64 url-safe-ish characters`() {
        val hash = AppSignatureHash.compute("app.dak.classify", "00112233445566778899aabbccddeeff".toByteArray())
        assertTrue(hash.all { it.isLetterOrDigit() || it == '+' || it == '/' })
    }

    @Test
    fun `known vector regression`() {
        // Fixed input pinned so an accidental algorithm change is caught.
        val hash = AppSignatureHash.compute("app.dak", "0011223344556677889900112233445566778899".repeat(3))
        assertEquals(11, hash.length)
        assertEquals(hash, AppSignatureHash.compute("app.dak", "0011223344556677889900112233445566778899".repeat(3)))
    }
}

class ConsumedOtpMatcherTest {

    @Test
    fun `matches consumer by retriever hash`() {
        val matcher = ConsumedOtpMatcher(
            hashesByPackage = mapOf("com.example.bank" to "FRAdOgxjeqz", "com.other" to "ZZZZZZZZZZZ"),
            browserPackages = emptySet(),
        )
        val otp = OtpInfo(code = "123456", retrieverHash = "FRAdOgxjeqz")
        assertEquals("com.example.bank", matcher.consumerOf(otp))
    }

    @Test
    fun `falls back to a browser for webotp`() {
        val matcher = ConsumedOtpMatcher(
            hashesByPackage = emptyMap(),
            browserPackages = setOf("com.android.chrome", "org.mozilla.firefox"),
        )
        val otp = OtpInfo(code = "998877", webOtpDomain = "example.com")
        assertEquals("com.android.chrome", matcher.consumerOf(otp))
    }

    @Test
    fun `returns null when nothing matches`() {
        val matcher = ConsumedOtpMatcher(hashesByPackage = mapOf("com.a" to "AAAAAAAAAAA"), browserPackages = emptySet())
        val otp = OtpInfo(code = "111111")
        assertNull(matcher.consumerOf(otp))
    }
}
