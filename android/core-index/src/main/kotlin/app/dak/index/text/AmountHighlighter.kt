package app.dak.index.text

import app.dak.finance.money.MoneyParser
import app.dak.search.AmountTokens

/**
 * Highlights amounts in a search hit that matched through a canonical amount token ([AmountTokens]) rather than
 * literally: searching "500000" highlights "Rs.5,00,000/-" in the body.
 */
internal object AmountHighlighter {

    /** The amount tokens among already-normalized positive query [tokens] (see [Highlighter.positiveTokens]). */
    fun amountTokens(tokens: Collection<String>): Set<String> = tokens.filterTo(HashSet()) { AmountTokens.isToken(it) }

    /** Inclusive ranges into [body] of amounts whose canonical token is in [amountTokens]; empty when none. */
    fun ranges(body: String, amountTokens: Set<String>): List<IntRange> {
        if (amountTokens.isEmpty() || body.isEmpty()) return emptyList()
        return try {
            MoneyParser.findAllForSearch(body).filter { m ->
                val h = m.hundredths ?: return@filter false
                AmountTokens.token(h) in amountTokens || (m.currency != null && AmountTokens.token(h, m.currency) in amountTokens)
            }.map { it.range }
        } catch (e: RuntimeException) {
            emptyList()
        }
    }
}
