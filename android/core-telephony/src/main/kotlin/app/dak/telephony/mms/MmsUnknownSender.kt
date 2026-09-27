package app.dak.telephony.mms

/**
 * An incoming MMS without a From address. The provider needs a non-empty address to give it a thread, so Dak files
 * it under [THREAD_ADDRESS]: a machine value (kept as older versions stored it), never shown as is. The app shows
 * `unknown_sender` in the app language for it and for a blank sender ([isUnknown]).
 */
object MmsUnknownSender {
    const val THREAD_ADDRESS: String = "Unknown"

    /** True for a sender the UI must name with the "unknown sender" string rather than show. */
    fun isUnknown(address: String?): Boolean = address.isNullOrBlank() || address.trim() == THREAD_ADDRESS
}
