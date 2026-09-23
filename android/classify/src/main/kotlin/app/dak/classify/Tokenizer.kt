package app.dak.classify

/**
 * A script-agnostic word tokenizer for classification text. Splits on Unicode letter/digit runs,
 * so it handles Latin, Devanagari and other Indic scripts uniformly (and therefore Hinglish, which
 * mixes Latin transliteration with English) without any per-language rules.
 */
public object Tokenizer {

    /** Zero-width joiner / non-joiner: legitimate inside an Indic or Arabic letter sequence (they
     * change how conjuncts render) but carry no meaning for matching, so a word is never split at
     * one and the character itself is dropped from the token. */
    private const val ZWNJ = '‌'
    private const val ZWJ = '‍'

    /** Lower-cases and splits [text] into word/number tokens, dropping punctuation and symbols. */
    public fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            when {
                Character.isLetter(ch) || Character.isDigit(ch) || isCombiningMark(ch) -> {
                    current.append(Character.toLowerCase(ch))
                }
                isJoiner(ch) -> {
                    // Keep joining the same token (don't split the word), but drop the joiner
                    // itself so tokens match whether or not the source used one.
                }
                else -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /** ZWJ / ZWNJ: joins a token without being part of it (see [tokenize]). */
    internal fun isJoiner(ch: Char): Boolean = ch == ZWNJ || ch == ZWJ

    /**
     * True for combining diacritical marks such as Devanagari matras (vowel signs) and virama,
     * which are not [Character.isLetter] on their own but belong to the letter they attach to.
     */
    internal fun isCombiningMark(ch: Char): Boolean = when (Character.getType(ch)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }
}
