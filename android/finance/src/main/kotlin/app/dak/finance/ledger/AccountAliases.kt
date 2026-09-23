package app.dak.finance.ledger

/**
 * User-confirmed "these two account ids are the same real account" decisions, as a map from an alias id to the id
 * it was merged into. [resolve] follows chains (A -> B -> C resolves A to C) and is safe against cycles, which a
 * sequence of merges and unmerges can never be relied upon to avoid.
 *
 * Aliases are only ever created by the user (from an [AliasSuggestion] or a manual "merge with"); nothing in this
 * module merges accounts on its own.
 */
class AccountAliases(private val aliasToCanonical: Map<String, String>) {

    /** The canonical id [accountId] was merged into, or [accountId] itself. */
    fun resolve(accountId: String): String {
        var current = accountId
        val seen = HashSet<String>()
        while (true) {
            val next = aliasToCanonical[current] ?: return current
            if (next == current || !seen.add(current)) return current
            current = next
        }
    }

    /** Every id that resolves to [canonicalId], including itself. */
    fun membersOf(canonicalId: String, knownIds: Collection<String> = emptyList()): Set<String> {
        val target = resolve(canonicalId)
        val candidates = HashSet<String>(knownIds).apply {
            addAll(aliasToCanonical.keys)
            addAll(aliasToCanonical.values)
            add(canonicalId)
        }
        return candidates.filterTo(LinkedHashSet()) { resolve(it) == target }
    }

    /** True when [accountId] is merged into another account. */
    fun isAlias(accountId: String): Boolean = resolve(accountId) != accountId

    val isEmpty: Boolean get() = aliasToCanonical.isEmpty()

    companion object {
        val NONE = AccountAliases(emptyMap())

        /**
         * Which of two ids to keep as canonical when the user says they are the same account: the one showing more
         * digits (more information), then the one with the earlier first activity, then the lexically smaller id.
         */
        fun canonicalOf(a: String, b: String, firstSeen: (String) -> Long? = { null }): String {
            val da = Account.partsOf(a)?.third.orEmpty()
            val db = Account.partsOf(b)?.third.orEmpty()
            if (da.length != db.length) return if (da.length > db.length) a else b
            val fa = firstSeen(a)
            val fb = firstSeen(b)
            if (fa != null && fb != null && fa != fb) return if (fa < fb) a else b
            return if (a <= b) a else b
        }
    }
}
