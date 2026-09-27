package app.dak.automations.presets

import app.dak.automations.rule.RuleCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresetsTest {

    @Test
    fun `built-in presets round-trip through the codec unchanged`() {
        val presets = Presets.all(now = 42L)
        assertTrue(presets.isNotEmpty())
        val encoded = RuleCodec.encode(presets)
        val decoded = RuleCodec.decode(encoded)
        assertEquals(presets, decoded)
    }

    @Test
    fun `preset ids are unique`() {
        val ids = Presets.all(now = 0L).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `localized names replace the English ones by id, blank or null keeps English`() {
        val english = Presets.all(now = 0L)
        val named = Presets.all(now = 0L) { id ->
            when (id) {
                Presets.ID_ARCHIVE_OLD_PROMOTIONS -> "Archiver"
                Presets.ID_LABEL_AMAZON_DELIVERIES -> " "
                else -> null
            }
        }
        assertEquals("Archiver", named.first { it.id == Presets.ID_ARCHIVE_OLD_PROMOTIONS }.name)
        assertEquals(english.drop(1), named.drop(1))
        assertEquals(english.map { it.id }, named.map { it.id })
    }
}
