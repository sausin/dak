package app.dak.finance.money

/**
 * Normalises non-ASCII decimal-digit characters to ASCII `0`-`9` before numeric parsing, so an
 * SMS written with Devanagari (०-९), Bengali, Gujarati, Gurmukhi, Tamil, Telugu, Kannada,
 * Malayalam, Arabic-Indic (٠-٩), Extended Arabic-Indic (۰-۹), full-width (０-９) or any other
 * Unicode decimal-digit script parses identically to one written with ASCII digits.
 *
 * Only characters of Unicode general category `Nd` (decimal digit number) are touched; letters,
 * punctuation and currency symbols are left untouched. Each `Nd` block is a contiguous run of ten
 * code points in digit order, so [Character.getNumericValue] reliably returns `0`..`9` for them.
 */
internal object DigitNormalizer {

    /** Returns [s] with every non-ASCII `Nd` digit replaced by its ASCII equivalent. */
    fun normalizeDigits(s: String): String {
        if (s.none { it.code > 127 }) return s
        var changed = false
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c.code > 127 && Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER.toInt()) {
                val value = Character.getNumericValue(c)
                if (value in 0..9) {
                    sb.append('0' + value)
                    changed = true
                    continue
                }
            }
            sb.append(c)
        }
        return if (changed) sb.toString() else s
    }
}
