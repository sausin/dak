package app.dak.finance.parser

/**
 * Reads the words around a position in an SMS body, within the same clause: what an account number or an amount
 * is attached to ("debited from **your A/c** XX1234", "**Avl Bal** Rs 500") is decided by the few words right next
 * to it, never by words in another sentence. Plain character scanning, bounded by [MAX_SCAN] chars per call, so
 * it is linear whatever the sender puts in the body.
 *
 * A clause ends at `!`, `?`, `;`, a newline, a comma followed by a blank, or a full stop followed by a blank —
 * except after a short abbreviation ("Rs.", "No.", "Avl.", "Dr.", "A/c."), which banks write mid-clause.
 */
internal object SmsWords {

    const val MAX_SCAN = 160

    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '/' || c == '@' || c == '\'' || c == '&' || c == '_'

    /**
     * Up to [max] lowercase words ending before [end] (exclusive), nearest first. Stops at a clause break, at
     * [floor], or after [MAX_SCAN] chars.
     */
    fun before(text: String, end: Int, max: Int, floor: Int = 0): List<String> {
        val out = ArrayList<String>(max)
        val lowest = maxOf(floor, end - MAX_SCAN, 0)
        var i = minOf(end, text.length) - 1
        while (i >= lowest && out.size < max) {
            val c = text[i]
            if (isWordChar(c)) {
                var s = i
                while (s - 1 >= lowest && isWordChar(text[s - 1])) s--
                out += text.substring(s, i + 1).lowercase()
                i = s - 1
                continue
            }
            if (isBreak(text, i)) break
            i--
        }
        return out
    }

    /** Up to [max] lowercase words starting at [start], in order. Stops at a clause break, at [ceil], or after [MAX_SCAN] chars. */
    fun after(text: String, start: Int, max: Int, ceil: Int = text.length): List<String> {
        val out = ArrayList<String>(max)
        val highest = minOf(ceil, start + MAX_SCAN, text.length)
        var i = maxOf(start, 0)
        while (i < highest && out.size < max) {
            val c = text[i]
            if (isWordChar(c)) {
                var e = i
                while (e + 1 < highest && isWordChar(text[e + 1])) e++
                out += text.substring(i, e + 1).lowercase()
                i = e + 1
                continue
            }
            if (isBreak(text, i)) break
            i++
        }
        return out
    }

    /** Whether the char at [i] ends a clause. */
    fun isBreak(text: String, i: Int): Boolean {
        val c = text[i]
        if (c == '!' || c == '?' || c == ';' || c == '\n') return true
        val next = text.getOrNull(i + 1)
        val blankNext = next == null || next.isWhitespace()
        if (c == ',') return blankNext
        if (c != '.' || !blankNext) return false
        // "Rs. 500", "A/c no. XX1234", "Avl. Bal." - a short alphabetic word before the point is an abbreviation.
        var s = i
        while (s - 1 >= 0 && (text[s - 1].isLetter() || text[s - 1] == '/')) s--
        val wordLength = i - s
        val wordBefore = s - 1 < 0 || !text[s - 1].isLetterOrDigit()
        return !(wordLength in 1..4 && wordBefore)
    }
}
