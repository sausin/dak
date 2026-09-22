package app.dak.classify

import app.dak.core.model.Category

/** A cloud classification result: a typed category choice with a probability. */
public data class CloudVerdict(val category: Category, val probability: Float)

/**
 * Opt-in, low-confidence-only cloud classification (Jev). Implementations must only ever receive
 * masked text (see [Masker]) plus the sender header — never raw message content.
 */
public fun interface CloudClassifier {
    public suspend fun classify(senderHeader: String?, maskedBody: String): CloudVerdict?
}

/** The default, free-tier classifier: cloud is opt-in, so this always declines. */
public object NoCloudClassifier : CloudClassifier {
    override suspend fun classify(senderHeader: String?, maskedBody: String): CloudVerdict? = null
}
