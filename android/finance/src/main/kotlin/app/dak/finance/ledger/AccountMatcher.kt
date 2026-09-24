package app.dak.finance.ledger

import app.dak.finance.money.DigitNormalizer
import app.dak.finance.parser.InstrumentDetector

/** What the matcher knows about one ledger account. */
data class AccountObservation(
    val accountId: String,
    val institution: String,
    val type: AccountType,
    /** Every digit the bank shows of the number (e.g. `440065`), or null when unknown. */
    val visibleDigits: String?,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
)

/** Why two accounts look like one. */
enum class AliasReason {
    /** The shorter visible number is a suffix of the longer one (`XX40065` vs `XX440065`). */
    SUFFIX_MATCH,

    /** One SMS format shows only the last 4 and another shows more, with the same last 4 (`XX0065` vs `XX440065`). */
    LAST4_MATCH,

    /** Identical visible digits under two instruments of the same kind (e.g. a UPI debit and an A/c debit). */
    SAME_DIGITS,
}

/**
 * A probable "same account" pair for the user to confirm. Never applied automatically.
 * [accountA] is the more recently active of the two.
 */
data class AliasSuggestion(
    val accountA: String,
    val accountB: String,
    val reason: AliasReason,
    /** Higher = more likely; only for ordering. */
    val score: Int,
) {
    /** Order-independent key of the pair (for persisted decisions). */
    val pairKey: String get() = AccountMatcher.pairKey(accountA, accountB)
}

/**
 * Detects probable-same-account pairs within one institution and account kind whose masked numbers differ but are
 * compatible: the shorter visible-digit string is a suffix of the longer (at least [MIN_SUFFIX] digits), which also
 * covers "last-4 equal, different mask length". Two numbers that differ within their common visible length are
 * provably different accounts and are never suggested; neither are pairs that appear together in one message
 * (a transfer between two own accounts) or pairs the user already decided.
 *
 * Scoring: 10 points per matching digit, +20 when the two timelines do not overlap (the bank switched format),
 * +10 when both were active within [RECENT_WINDOW_MILLIS] of each other.
 */
object AccountMatcher {

    const val MIN_SUFFIX = 4
    const val RECENT_WINDOW_MILLIS = 180L * 24 * 60 * 60 * 1000

    /**
     * @param decidedPairs [pairKey]s the user already answered (same or different) — never asked again.
     * @param coOccurringPairs [pairKey]s of accounts whose numbers appear together in at least one message.
     * @param aliases existing merges; an account already merged into another is not suggested again.
     */
    fun suggest(
        accounts: List<AccountObservation>,
        decidedPairs: Set<String> = emptySet(),
        coOccurringPairs: Set<String> = emptySet(),
        aliases: AccountAliases = AccountAliases.NONE,
    ): List<AliasSuggestion> {
        val candidates = accounts.filter { !aliases.isAlias(it.accountId) && !it.visibleDigits.isNullOrEmpty() }
        val out = ArrayList<AliasSuggestion>()
        val byGroup = candidates.groupBy { it.institution.trim().uppercase() to it.type.aliasFamily }
        for ((_, group) in byGroup) {
            for (i in group.indices) {
                for (j in i + 1 until group.size) {
                    val suggestion = compare(group[i], group[j]) ?: continue
                    if (suggestion.pairKey in decidedPairs || suggestion.pairKey in coOccurringPairs) continue
                    out += suggestion
                }
            }
        }
        return out.sortedWith(compareByDescending<AliasSuggestion> { it.score }.thenBy { it.pairKey })
    }

    /** The suggestion for one pair, or null when the two cannot be the same account. */
    fun compare(x: AccountObservation, y: AccountObservation): AliasSuggestion? {
        if (x.accountId == y.accountId) return null
        if (x.institution.trim().uppercase() != y.institution.trim().uppercase() || x.type.aliasFamily != y.type.aliasFamily) return null
        val dx = x.visibleDigits ?: return null
        val dy = y.visibleDigits ?: return null
        val (shorter, longer) = if (dx.length <= dy.length) dx to dy else dy to dx
        if (shorter.length < MIN_SUFFIX || !longer.endsWith(shorter)) return null
        val reason = when {
            shorter == longer -> AliasReason.SAME_DIGITS
            shorter.length == 4 -> AliasReason.LAST4_MATCH
            else -> AliasReason.SUFFIX_MATCH
        }
        var score = shorter.length * 10
        val older = if (x.firstSeenMillis <= y.firstSeenMillis) x else y
        val newer = if (older === x) y else x
        if (older.lastSeenMillis <= newer.firstSeenMillis) score += 20
        if (newer.firstSeenMillis - older.lastSeenMillis <= RECENT_WINDOW_MILLIS) score += 10
        val (a, b) = if (x.lastSeenMillis >= y.lastSeenMillis) x to y else y to x
        return AliasSuggestion(a.accountId, b.accountId, reason, score)
    }

    /** Order-independent key for a pair of account ids. */
    fun pairKey(a: String, b: String): String = if (a <= b) "$a|$b" else "$b|$a"

    /**
     * [pairKey]s of every two accounts in [accounts] whose visible digits both occur, as masked numbers, in one of
     * [bodies] (e.g. "transferred from A/c XX1234 to A/c XX5678"). Such pairs are distinct accounts.
     */
    fun coOccurringPairs(accounts: List<AccountObservation>, bodies: Sequence<String>): Set<String> {
        val withDigits = accounts.filter { !it.visibleDigits.isNullOrEmpty() }
        if (withDigits.size < 2) return emptySet()
        val out = HashSet<String>()
        for (body in bodies) {
            val found = MaskedNumbers.findAll(body)
            if (found.size < 2) continue
            val present = withDigits.filter { acc -> found.any { it == acc.visibleDigits } }
            for (i in present.indices) for (j in i + 1 until present.size) {
                out += pairKey(present[i].accountId, present[j].accountId)
            }
        }
        return out
    }
}

/**
 * Finds masked account/card numbers (`XX1234`, `**440065`, `A/c X5073`, `A/c ending 1234`, `Card 4375XXXX1234`) in a
 * message body — the same numbers, found the same way, as the transaction parser reads.
 */
object MaskedNumbers {

    /** The visible digits of every masked number in [body], in order of appearance, without duplicates. */
    fun findAll(body: String): List<String> =
        InstrumentDetector.findRefs(DigitNormalizer.normalizeDigits(body))
            .map { ref -> ref.masked.filter { it.isDigit() } }
            .filter { it.length >= AccountMatcher.MIN_SUFFIX }
            .distinct()
}
