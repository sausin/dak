package app.dak.search

import java.text.Normalizer

/**
 * Unicode-aware normalizer used for both indexing message text and normalizing search terms, so
 * the two sides of a match agree.
 *
 * Steps: lower-case, NFKC (fold compatibility variants such as full-width forms), then strip
 * Latin combining diacritics (the `U+0300..U+036F` block, e.g. an accent decomposed off "café")
 * while leaving Indic combining marks alone (Devanagari matras and friends live in their own
 * blocks, e.g. `U+0900..U+097F`, and are never decomposed into the Latin diacritics block by NFD),
 * then re-compose (NFC) for a stable, comparable form.
 */
object TextNormalizer {
    fun normalize(s: String): String {
        if (s.isEmpty()) return s
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val lower = nfkc.lowercase()
        val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val stripped = buildString(nfd.length) {
            for (c in nfd) {
                if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.COMBINING_DIACRITICAL_MARKS) continue
                append(c)
            }
        }
        return Normalizer.normalize(stripped, Normalizer.Form.NFC)
    }
}
