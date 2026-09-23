package app.dak.core.model

import kotlinx.serialization.Serializable

/** Inbox tabs. Order is display order. */
@Serializable
enum class Category {
    PERSONAL, TRANSACTION, OTP, PROMOTION, SPAM, UNKNOWN;
}

@Serializable
enum class ClassifierSource { TEMPLATE, MODEL, CLOUD, USER, NONE }

/** Result of the classification pipeline for one message. */
@Serializable
data class Classification(
    val category: Category,
    /** 0.0..1.0 */
    val confidence: Float,
    val source: ClassifierSource,
    val otp: OtpInfo? = null,
    /** Brand / canonical sender if known, e.g. "HDFC Bank" for VM-HDFCBK. */
    val canonicalSender: String? = null,
    val labels: Set<String> = emptySet(),
) {
    companion object {
        val Unclassified = Classification(Category.UNKNOWN, 0f, ClassifierSource.NONE)
    }
}

@Serializable
data class OtpInfo(
    val code: String,
    /** 11-char SMS Retriever app hash found at the end of the body, if any. */
    val retrieverHash: String? = null,
    /** WebOTP origin from a trailing `@domain #code` line, if any. */
    val webOtpDomain: String? = null,
)
