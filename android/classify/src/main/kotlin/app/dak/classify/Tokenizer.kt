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
            if (Character.isLetter(ch) || Character.isDigit(ch)) {
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
}
