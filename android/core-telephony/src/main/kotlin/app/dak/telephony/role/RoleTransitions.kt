package app.dak.telephony.role

/** What changed between two role readings. */
enum class RoleChange { NONE, LOST, REGAINED }

/** Pure transition rule, kept apart for tests. */
object RoleTransitions {
    fun between(wasDefault: Boolean, isDefault: Boolean): RoleChange = when {
        wasDefault == isDefault -> RoleChange.NONE
        isDefault -> RoleChange.REGAINED
        else -> RoleChange.LOST
    }
}
