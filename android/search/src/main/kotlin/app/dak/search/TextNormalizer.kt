package app.dak.search

import java.text.Normalizer

/**
 * Unicode-aware normalizer used for both indexing message text and normalizing search terms, so
 * the two sides of a match agree.
 *
 * Steps: lower-case, NFKC (fold compatibility variants such as full-width forms), then strip
 * Latin combining diacritics (the `U+0300..U+036F` block, e.g. an accent decomposed off "café")
 * and, by default, Arabic diacritics (tashkil: fatha/damma/kasra/sukun/shadda/tanwin, which are
 * `Mn`-category marks inside the Arabic Unicode block) so a query without them still matches a
 * body written with them - while leaving Indic combining marks alone (Devanagari matras and
 * friends live in their own blocks, e.g. `U+0900..U+097F`, are never decomposed into the Latin
 * diacritics block by NFD, and are not in the Arabic block either, so this never touches them).
 * Zero-width joiner/non-joiner (U+200C/U+200D) are always dropped: they are meaningful for how an
 * Indic or Arabic conjunct *renders* but carry no meaning for matching, so text differing only in
 * their presence must normalize to the same key. Finally re-compose (NFC) for a stable form.
 */
object TextNormalizer {

    private const val ZWNJ = '‌'
    private const val ZWJ = '‍'

    /**
     * @param stripArabicDiacritics when true (the default), Arabic tashkil marks are removed so
     * e.g. "مرحبا" and "مَرْحَبًا" (the same word, with optional vowel marks) match.
     */
    fun normalize(s: String, stripArabicDiacritics: Boolean = true): String {
        if (s.isEmpty()) return s
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val lower = nfkc.lowercase()
        val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val stripped = buildString(nfd.length) {
            for (c in nfd) {
                when {
                    Character.UnicodeBlock.of(c) == Character.UnicodeBlock.COMBINING_DIACRITICAL_MARKS -> continue
                    c == ZWNJ || c == ZWJ -> continue
                    stripArabicDiacritics && isArabicDiacritic(c) -> continue
                    else -> append(c)
                }
            }
        }
        return Normalizer.normalize(stripped, Normalizer.Form.NFC)
    }

    /** True for Arabic-block combining marks (tashkil) - not for Arabic letters, which are `Lo`. */
    private fun isArabicDiacritic(c: Char): Boolean =
        Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC &&
            Character.getType(c) == Character.NON_SPACING_MARK.toInt()
}
