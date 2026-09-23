package app.dak.automations.rule

import app.dak.core.model.Category

/**
 * Whether a condition tree can, for some message, be satisfied by an OTP. Used to flag rules whose
 * forwarding/relay actions must require biometric confirmation (see the OTP lifecycle safety rule).
 *
 * False when the tree explicitly excludes OTPs ([excludesOtp]: e.g. [otpExclusion] as a top-level conjunct).
 * Otherwise true when the tree contains a positive [Condition.HasOtp], a positive [Condition.CategoryIs] with
 * [Category.OTP], or restricts by category to nothing at all (no positive [Condition.CategoryIs] anywhere, i.e.
 * any category including OTP can reach it). Conditions under a [Condition.Not] never count as a positive
 * restriction, so `Not(CategoryIs(PROMOTION))` alone is (conservatively) OTP-capable.
 */
public fun conditionsCanMatchOtp(condition: Condition): Boolean {
    if (excludesOtp(condition)) return false
    if (containsPositiveHasOtp(condition)) return true
    val categories = positiveCategories(condition)
    return categories.isEmpty() || Category.OTP in categories
}

/**
 * The canonical "never OTPs" clause: no extracted OTP code and not classified as OTP. Add it as a conjunct of a
 * rule's top-level [Condition.All] to make [conditionsCanMatchOtp] false.
 */
public fun otpExclusion(): List<Condition> = listOf(
    Condition.Not(Condition.HasOtp),
    Condition.Not(Condition.CategoryIs(Category.OTP)),
)

/**
 * True when the top-level conjuncts (nested [Condition.All]s flattened) guarantee no OTP can match: there is a
 * `Not(HasOtp)` (no extracted code) and the OTP category is ruled out, either by `Not(CategoryIs(OTP))` or by a
 * positive `CategoryIs` of another category / an `Any` of only non-OTP categories.
 */
public fun excludesOtp(condition: Condition): Boolean {
    val conjuncts = topLevelConjuncts(condition)
    val noCode = conjuncts.any { it is Condition.Not && it.child == Condition.HasOtp }
    if (!noCode) return false
    return conjuncts.any { c ->
        when (c) {
            is Condition.Not -> c.child == Condition.CategoryIs(Category.OTP)
            is Condition.CategoryIs -> c.category != Category.OTP
            is Condition.Any -> c.children.isNotEmpty() &&
                c.children.all { it is Condition.CategoryIs && it.category != Category.OTP }
            else -> false
        }
    }
}

/** The conjuncts of [condition] at the top level: nested [Condition.All]s are flattened, anything else is one item. */
public fun topLevelConjuncts(condition: Condition): List<Condition> = when (condition) {
    is Condition.All -> condition.children.flatMap(::topLevelConjuncts)
    else -> listOf(condition)
}

private fun containsPositiveHasOtp(condition: Condition): Boolean = when (condition) {
    is Condition.HasOtp -> true
    is Condition.All -> condition.children.any(::containsPositiveHasOtp)
    is Condition.Any -> condition.children.any(::containsPositiveHasOtp)
    else -> false
}

private fun positiveCategories(condition: Condition): Set<Category> = when (condition) {
    is Condition.CategoryIs -> setOf(condition.category)
    is Condition.All -> condition.children.flatMapTo(mutableSetOf(), ::positiveCategories)
    is Condition.Any -> condition.children.flatMapTo(mutableSetOf(), ::positiveCategories)
    else -> emptySet()
}
