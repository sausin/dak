package app.dak.automations.rule

import app.dak.core.model.Category

/**
 * Whether a condition tree can, for some message, be satisfied by an OTP. Used to flag rules whose
 * forwarding/relay actions must require biometric confirmation (see the OTP lifecycle safety rule):
 * true when the tree contains [Condition.HasOtp], contains [Condition.CategoryIs] with
 * [Category.OTP], or restricts by category to nothing at all (no [Condition.CategoryIs] anywhere,
 * i.e. any category including OTP can reach it).
 */
public fun conditionsCanMatchOtp(condition: Condition): Boolean {
    if (containsHasOtp(condition)) return true
    val categories = categoriesMentioned(condition)
    return categories.isEmpty() || Category.OTP in categories
}

private fun containsHasOtp(condition: Condition): Boolean = when (condition) {
    is Condition.HasOtp -> true
    is Condition.All -> condition.children.any(::containsHasOtp)
    is Condition.Any -> condition.children.any(::containsHasOtp)
    is Condition.Not -> containsHasOtp(condition.child)
    else -> false
}

private fun categoriesMentioned(condition: Condition): Set<Category> = when (condition) {
    is Condition.CategoryIs -> setOf(condition.category)
    is Condition.All -> condition.children.flatMapTo(mutableSetOf(), ::categoriesMentioned)
    is Condition.Any -> condition.children.flatMapTo(mutableSetOf(), ::categoriesMentioned)
    is Condition.Not -> categoriesMentioned(condition.child)
    else -> emptySet()
}
