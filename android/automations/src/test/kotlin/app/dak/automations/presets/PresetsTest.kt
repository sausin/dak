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
}
