package app.dak.index

import app.dak.core.model.Category
import app.dak.index.otp.OtpTiming

/*
 * Seams the app may bind with Hilt (`@Binds` in a SingletonComponent module). Each is declared with
 * `@BindsOptionalOf` in IndexModule, so an unbound seam falls back to the documented default.
 */

/** Recycle-bin retention (Settings -> Backup and data). */
interface BinPolicy {
    /** How long an entry of [category] stays in the bin; `null` = until the user empties it. */
    suspend fun retentionMillis(category: Category): Long?

    companion object {
        const val DEFAULT_OTP_RETENTION_MILLIS: Long = 24 * 60 * 60_000L
        const val DEFAULT_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60_000L
    }
}

/** Default bin policy: OTPs 1 day, everything else 30 days (the free-tier values). */
object DefaultBinPolicy : BinPolicy {
    override suspend fun retentionMillis(category: Category): Long? =
        if (category == Category.OTP) BinPolicy.DEFAULT_OTP_RETENTION_MILLIS else BinPolicy.DEFAULT_RETENTION_MILLIS
}

/** How loud a consumed OTP is and whether it is tidied up (Notifications -> Advanced). */
enum class ConsumedOtpMode { SILENT_AUTO_DELETE, SILENT_ONLY, NORMAL }

/** OTP lifecycle settings. */
interface OtpPolicy {
    /** Delay after arrival before an OTP message moves to the bin; `null` = auto-delete off. */
    suspend fun otpAutoDeleteAfterMillis(): Long?

    suspend fun consumedOtpMode(): ConsumedOtpMode

    /** Delay for consumed OTPs in [ConsumedOtpMode.SILENT_AUTO_DELETE]; clamped to at least 5 minutes. */
    suspend fun consumedOtpDeleteAfterMillis(): Long
}

/** Default OTP policy: auto-delete after 24 h; consumed OTPs silent + deleted after 10 min. */
object DefaultOtpPolicy : OtpPolicy {
    override suspend fun otpAutoDeleteAfterMillis(): Long? = OtpTiming.DEFAULT_OTP_DELETE_MILLIS
    override suspend fun consumedOtpMode(): ConsumedOtpMode = ConsumedOtpMode.SILENT_AUTO_DELETE
    override suspend fun consumedOtpDeleteAfterMillis(): Long = OtpTiming.DEFAULT_CONSUMED_DELETE_MILLIS
}

/**
 * Contacts access, provided by the app (it owns the READ_CONTACTS permission flow). All methods are called from
 * background threads and may block; return empty results when the permission is missing.
 */
interface ContactLookup {
    /** Contact display name for a raw address, or null. */
    fun displayName(address: String): String?

    /** True if [address] belongs to a saved contact (feeds the classifier's "personal" signal). */
    fun isContact(address: String): Boolean

    /** Phone numbers of contacts whose name contains [nameQuery] (for `from:<contact name>`). */
    fun addressesMatching(nameQuery: String): List<String>

    /** Contact names starting with / containing [prefix], for typed-ahead suggestions. */
    fun namesMatching(prefix: String, limit: Int): List<String>
}

/** Used when the app binds no [ContactLookup]. */
object NoContactLookup : ContactLookup {
    override fun displayName(address: String): String? = null
    override fun isContact(address: String): Boolean = false
    override fun addressesMatching(nameQuery: String): List<String> = emptyList()
    override fun namesMatching(prefix: String, limit: Int): List<String> = emptyList()
}
