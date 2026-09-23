package app.dak.index.bin

/**
 * Who removed a message into the recycle bin. Stored as a string in `BinEntry.deletedBy`:
 * `manual`, `auto-rule:<rule name>`, `auto-consumed:<package>` or `auto-otp`.
 */
sealed class DeletedBy {
    abstract val encoded: String

    data object Manual : DeletedBy() {
        override val encoded: String = "manual"
    }

    data class AutoRule(val ruleName: String) : DeletedBy() {
        override val encoded: String get() = "auto-rule:$ruleName"
    }

    data class AutoConsumed(val packageName: String) : DeletedBy() {
        override val encoded: String get() = "auto-consumed:$packageName"
    }

    data object AutoOtp : DeletedBy() {
        override val encoded: String = "auto-otp"
    }

    companion object {
        /** Parses a stored value; unknown values decode as [Manual]. */
        fun decode(value: String): DeletedBy = when {
            value == "auto-otp" -> AutoOtp
            value.startsWith("auto-rule:") -> AutoRule(value.removePrefix("auto-rule:"))
            value.startsWith("auto-consumed:") -> AutoConsumed(value.removePrefix("auto-consumed:"))
            else -> Manual
        }
    }
}

/** Pure retention arithmetic for bin entries. */
object BinRetention {
    /** Purge time for an entry deleted at [deletedAtMillis]; `null` retention means "until the user empties it". */
    fun purgeAt(deletedAtMillis: Long, retentionMillis: Long?): Long =
        if (retentionMillis == null || retentionMillis < 0) Long.MAX_VALUE
        else (deletedAtMillis + retentionMillis).let { if (it < deletedAtMillis) Long.MAX_VALUE else it }
}
