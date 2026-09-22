package app.dak.classify

/**
 * A script-agnostic word tokenizer for classification text. Splits on Unicode letter/digit runs,
 * so it handles Latin, Devanagari and other Indic scripts uniformly (and therefore Hinglish, which
 * mixes Latin transliteration with English) without any per-language rules.
 */
public object Tokenizer {

    /** Lower-cases and splits [text] into word/number tokens, dropping punctuation and symbols. */
    public fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            if (Character.isLetter(ch) || Character.isDigit(ch) || isCombiningMark(ch)) {
                current.append(Character.toLowerCase(ch))
            } else {
                if (current.isNotEmpty()) {
                    tokens += current.toString()
                    current.clear()
                }
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /**
     * True for combining diacritical marks such as Devanagari matras (vowel signs) and virama,
     * which are not [Character.isLetter] on their own but belong to the letter they attach to.
     */
    private fun isCombiningMark(ch: Char): Boolean = when (Character.getType(ch)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }
}
