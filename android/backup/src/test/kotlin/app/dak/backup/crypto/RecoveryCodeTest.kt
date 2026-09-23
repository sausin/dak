package app.dak.backup.crypto

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryCodeTest {

    @Test
    fun `formats as six groups of four`() {
        val generated = RecoveryCode.generate(SecureRandom())
        val groups = generated.formatted.split("-")
        assertEquals(6, groups.size)
        assertTrue(groups.all { it.length == 4 })
    }

    @Test
    fun `parse recovers the exact original secret`() {
        val generated = RecoveryCode.generate(SecureRandom())
        val parsed = RecoveryCode.parse(generated.formatted).getOrThrow()
        assertContentEquals(generated.secret, parsed)
    }

    @Test
    fun `parse tolerates lowercase, spaces and look-alike substitutions`() {
        val generated = RecoveryCode.generate(SecureRandom())
        val messy = generated.formatted.lowercase().replace("-", " ")
            .replace('1', 'i') // exercise the I -> 1 normalization on parse
        val parsed = RecoveryCode.parse(messy).getOrThrow()
        assertContentEquals(generated.secret, parsed)
    }

    @Test
    fun `rejects a bad checksum`() {
        val generated = RecoveryCode.generate(SecureRandom())
        val lastChar = generated.formatted.last()
        val corruptedChar = if (lastChar == '0') '1' else '0'
        val corrupted = generated.formatted.dropLast(1) + corruptedChar
        assertTrue(RecoveryCode.parse(corrupted).isFailure)
    }

    @Test
    fun `rejects the wrong length`() {
        assertTrue(RecoveryCode.parse("ABCD-EFGH").isFailure)
    }

    @Test
    fun `generated codes are not trivially predictable`() {
        val a = RecoveryCode.generate(SecureRandom())
        val b = RecoveryCode.generate(SecureRandom())
        assertFalse(a.formatted == b.formatted)
    }
}
