package app.dak.search

import kotlin.test.Test
import kotlin.test.assertEquals

class TextNormalizerTest {
    @Test
    fun `lowercases ascii`() {
        assertEquals("hello world", TextNormalizer.normalize("Hello World"))
    }

    @Test
    fun `strips latin diacritics`() {
        assertEquals("cafe", TextNormalizer.normalize("Café"))
        assertEquals("resume", TextNormalizer.normalize("Résumé"))
    }

    @Test
    fun `folds full width compatibility forms via nfkc`() {
        // Full-width "ABC" (U+FF21..) folds to ASCII lowercase "abc".
        assertEquals("abc", TextNormalizer.normalize("ＡＢＣ"))
    }

    @Test
    fun `preserves indic combining marks`() {
        val word = "नमस्ते"
        val normalized = TextNormalizer.normalize(word)
        // The combining virama/vowel signs must survive (only Latin U+0300-036F is stripped).
        assertEquals(word.lowercase(), normalized)
    }

    @Test
    fun `empty string is safe`() {
        assertEquals("", TextNormalizer.normalize(""))
    }

    @Test
    fun `idempotent`() {
        val once = TextNormalizer.normalize("Café Résumé")
        assertEquals(once, TextNormalizer.normalize(once))
    }
}
