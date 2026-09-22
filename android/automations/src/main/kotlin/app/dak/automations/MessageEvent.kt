package app.dak.automations

import app.dak.core.model.Category
import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.OtpInfo

/**
 * The read-only slice of an indexed message the [RuleEngine] evaluates rules against. Built by the
 * `:core-index` layer from a [app.dak.core.model.Message] plus its [app.dak.core.model.Classification]
 * and, if any, [ExtractedTransaction] — this module never touches the provider or the index directly.
 */
public data class MessageEvent(
    /** [app.dak.core.model.MessageKey.toString], so this module stays free of any Android/provider type. */
    val messageKey: String,
    val address: String,
    /** Display-layer merge key (see sender merge groups), or `null` if the sender isn't merged. */
    val mergeKey: String? = null,
    val body: String,
    val dateMillis: Long,
    val subId: Int,
    val slot: Int? = null,
    val category: Category,
    val otp: OtpInfo? = null,
    val transaction: ExtractedTransaction? = null,
)
