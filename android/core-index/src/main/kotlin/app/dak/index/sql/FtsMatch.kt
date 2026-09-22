package app.dak.index.sql

import app.dak.index.text.Highlighter
import app.dak.search.TextExpr

/**
 * Translates a [TextExpr] into a SQL boolean expression.
 *
 * In [Mode.Fts] it uses only the FTS *standard* query syntax (implicit AND, `OR`, quoted phrases, `term*` prefixes)
 * inside each MATCH string, so it works whether or not SQLCipher was built with `SQLITE_ENABLE_FTS3_PARENTHESIS`.
 * Anything standard syntax cannot express (an OR of AND-groups, negation) is expressed in SQL instead, as boolean
 * combinations of the mode's MATCH predicate. FTS standard syntax gives `OR` higher precedence than the implicit
 * AND, so `a OR b c` means `(a OR b) AND c`; the merge rules below respect that.
 *
 * In [Mode.Like] (tables without FTS, such as the bin) every term becomes a `LIKE` over a normalized text column.
 */
internal class FtsMatch(private val mode: Mode) {

    sealed class Mode {
        /** [predicate] must contain exactly one `?`, bound to the MATCH string. */
        data class Fts(val predicate: String) : Mode()

        /** [column] holds [app.dak.search.TextNormalizer]-normalized text. */
        data class Like(val column: String) : Mode()
    }

    /** Renders [expr]; null when it contains no searchable tokens. */
    fun toSql(expr: TextExpr): SqlQuery? = build(expr)?.let(::toSqlQuery)

    /** Renders [expr] as a single FTS MATCH string when possible (used by tests and diagnostics). */
    fun matchStringOrNull(expr: TextExpr): String? = (build(expr) as? Node.Match)?.match

    private enum class Kind { SINGLE, OR_CHAIN, AND_GROUP }

    private sealed class Node {
        data class Match(val match: String, val kind: Kind) : Node()
        data class Sql(val query: SqlQuery) : Node()
    }

    private fun build(expr: TextExpr): Node? = when (expr) {
        is TextExpr.Term -> leaf(Highlighter.tokenize(expr.value), prefix = true)
        is TextExpr.Phrase -> leaf(Highlighter.tokenize(expr.value), prefix = false)
        is TextExpr.And -> combine(build(expr.left), build(expr.right), isAnd = true)
        is TextExpr.Or -> combine(build(expr.left), build(expr.right), isAnd = false)
        is TextExpr.Not -> build(expr.expr)?.let { inner ->
            val q = toSqlQuery(inner)
            Node.Sql(SqlQuery("NOT (${q.sql})", q.args))
        }
    }

    private fun leaf(tokens: List<String>, prefix: Boolean): Node? {
        if (tokens.isEmpty()) return null
        return when (mode) {
            is Mode.Like -> {
                val pattern = "%" + tokens.joinToString("%") { escapeLike(it) } + "%"
                Node.Sql(SqlQuery("${mode.column} LIKE ? ESCAPE '\\'", listOf(pattern)))
            }
            is Mode.Fts -> {
                val match = if (tokens.size == 1) {
                    val t = tokens[0]
                    if (prefix && t.length >= PREFIX_MIN_LENGTH) "$t*" else "\"$t\""
                } else {
                    "\"" + tokens.joinToString(" ") + "\""
                }
                Node.Match(match, Kind.SINGLE)
            }
        }
    }

    private fun combine(left: Node?, right: Node?, isAnd: Boolean): Node? {
        if (left == null) return right
        if (right == null) return left
        if (left is Node.Match && right is Node.Match) {
            if (isAnd) return Node.Match("${left.match} ${right.match}", Kind.AND_GROUP)
            if (left.kind != Kind.AND_GROUP && right.kind != Kind.AND_GROUP) {
                return Node.Match("${left.match} OR ${right.match}", Kind.OR_CHAIN)
            }
        }
        val l = toSqlQuery(left)
        val r = toSqlQuery(right)
        val op = if (isAnd) "AND" else "OR"
        return Node.Sql(SqlQuery("(${l.sql}) $op (${r.sql})", l.args + r.args))
    }

    private fun toSqlQuery(node: Node): SqlQuery = when (node) {
        is Node.Sql -> node.query
        is Node.Match -> SqlQuery((mode as Mode.Fts).predicate, listOf(node.match))
    }

    companion object {
        /** Minimum term length that gets a trailing `*` (prefix match, for typed-ahead search). */
        const val PREFIX_MIN_LENGTH = 2
    }
}
