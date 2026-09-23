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

    @Test
    fun `strips arabic diacritics by default so a bare query matches a fully-voweled body`() {
        // "مرحبا" (hello) with and without tashkil (fatha/sukun) must normalize the same.
        val plain = "مرحبا"
        val voweled = "مَرْحَبًا"
        assertEquals(TextNormalizer.normalize(plain), TextNormalizer.normalize(voweled))
    }

    @Test
    fun `arabic diacritics can be kept when asked`() {
        val voweled = "مَرْحَبًا"
        val kept = TextNormalizer.normalize(voweled, stripArabicDiacritics = false)
        val stripped = TextNormalizer.normalize(voweled, stripArabicDiacritics = true)
        assertEquals(true, kept.length > stripped.length)
    }

    @Test
    fun `zwnj and zwj are ignored for matching`() {
        val withZwnj = "می‌روم"
        val withoutZwnj = "میروم"
        assertEquals(TextNormalizer.normalize(withoutZwnj), TextNormalizer.normalize(withZwnj))
    }

    @Test
    fun `hindi query matches hindi body regardless of case-folding`() {
        val body = "आपका ओटीपी 665544 है"
        val query = "ओटीपी"
        assertEquals(true, TextNormalizer.normalize(body).contains(TextNormalizer.normalize(query)))
    }

    @Test
    fun `tamil query matches tamil body`() {
        val body = "உங்கள் கணக்கில் ரூ500 வரவு வைக்கப்பட்டது"
        val query = "கணக்கில்"
        assertEquals(true, TextNormalizer.normalize(body).contains(TextNormalizer.normalize(query)))
    }

    @Test
    fun `urdu query matches urdu body even with different diacritics`() {
        val body = "آپ کا اکاؤنٹ خرچ ہوا"
        val query = "اکاؤنٹ"
        assertEquals(true, TextNormalizer.normalize(body).contains(TextNormalizer.normalize(query)))
    }
}
