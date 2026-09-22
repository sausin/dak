package app.dak.search

/**
 * Translates the free-text side of a [SearchQuery] into an SQLite FTS4 `MATCH` expression.
 *
 * Android's bundled SQLite does not ship FTS4's *enhanced* query syntax (parentheses, explicit
 * `AND`), so this assumes the *standard* query syntax only: terms separated by a space are
 * AND-ed together, `OR` is a literal keyword between two terms, and a `-` prefix negates a term
 * *that must follow at least one positive term* (a MATCH expression cannot start with `NOT`).
 * Structured filters ([Filter]) are never part of the FTS expression; they resolve against
 * enrichment columns instead (see the build plan).
 */
object FtsMatch {

    private val SPECIAL_CHARS = Regex("""["*:()\-^]""")

    /** Normalizes and strips FTS special characters from a raw user/indexed term. */
    fun sanitizeTerm(raw: String): String =
        SPECIAL_CHARS.replace(TextNormalizer.normalize(raw), "").trim()

    /**
     * @param prefixLastTerm append `*` to the final positive term for type-ahead matching.
     * @return a MATCH expression, or null if [textExpr] is null or has no expressible positive
     *   anchor (e.g. only negated terms, which standard FTS4 syntax cannot represent safely).
     */
    fun build(textExpr: TextExpr?, prefixLastTerm: Boolean = false): String? {
        if (textExpr == null) return null
        val atoms = mutableListOf<Atom>()
        flatten(textExpr, null, atoms)
        if (atoms.isEmpty()) return null

        val positiveIndex = atoms.indexOfFirst { !it.negated }
        if (positiveIndex == -1) return null // no positive anchor: not representable
        if (positiveIndex != 0) {
            val anchor = atoms.removeAt(positiveIndex)
            atoms.add(0, anchor.copy(connector = null))
            // The atom that used to be first now needs its original connector role preserved
            // relative to its new predecessor; AND is the safe default since standard-syntax
            // grouping cannot be reconstructed anyway once atoms are reordered.
        }

        if (prefixLastTerm) {
            val lastPositive = atoms.indexOfLast { !it.negated }
            if (lastPositive != -1) {
                atoms[lastPositive] = atoms[lastPositive].copy(text = atoms[lastPositive].text + "*")
            }
        }

        val sb = StringBuilder()
        atoms.forEachIndexed { i, atom ->
            if (atom.text.isBlank()) return@forEachIndexed
            when {
                i == 0 -> sb.append(atom.text)
                atom.connector == "OR" -> sb.append(" OR ").append(atom.text)
                atom.negated -> sb.append(" -").append(atom.text)
                else -> sb.append(" ").append(atom.text)
            }
        }
        val result = sb.toString().trim()
        return result.ifBlank { null }
    }

    private data class Atom(val connector: String?, val negated: Boolean, val text: String)

    private fun flatten(expr: TextExpr, connector: String?, out: MutableList<Atom>) {
        when (expr) {
            is TextExpr.And -> {
                flatten(expr.left, connector, out)
                flatten(expr.right, "AND", out)
            }
            is TextExpr.Or -> {
                flatten(expr.left, connector, out)
                flatten(expr.right, "OR", out)
            }
            is TextExpr.Not -> flattenLeaf(expr.expr, connector, negated = true, out)
            is TextExpr.Term, is TextExpr.Phrase -> flattenLeaf(expr, connector, negated = false, out)
        }
    }

    private fun flattenLeaf(expr: TextExpr, connector: String?, negated: Boolean, out: MutableList<Atom>) {
        val text = when (expr) {
            is TextExpr.Term -> sanitizeTerm(expr.value)
            is TextExpr.Phrase -> {
                val cleaned = TextNormalizer.normalize(expr.value).replace("\"", "")
                if (cleaned.isBlank()) "" else "\"$cleaned\""
            }
            // A negated OR/AND cannot occur from our parser (Not always wraps a single atom), but
            // handle it defensively by flattening its first atom only.
            else -> ""
        }
        if (text.isNotBlank()) out += Atom(connector, negated, text)
    }
}
