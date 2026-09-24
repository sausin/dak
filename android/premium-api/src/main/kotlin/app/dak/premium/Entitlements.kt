package app.dak.premium

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything that is gated. Free tier: server-backed features plus a few value gates.
 *
 * [summary] is the English text (fallback and tests); the app shows the string resource named [stringKey]
 * (`feature_<name>_summary` in strings_settings.xml, see docs/i18n.md).
 */
enum class Feature(val summary: String) {
    ADJUSTABLE_OTP_BIN_RETENTION("Keep deleted OTPs in the bin for longer than 1 day"),
    WEBHOOKS("Send signed webhooks from automations"),
    RELAY_RULES("Forward chosen message types to a person automatically"),
    TRANSLATION("Translate messages on-device"),
    AI_SEARCH("Ask questions in plain language"),
    WEB_CLIENT("Read and reply from your computer"),
    SEND_API("Send SMS from your own services"),
    CLOUD_CLASSIFICATION_HIGHER_CAP("Higher monthly cap for cloud classification"),
    ;

    /** Android string-resource name of [summary]; stable because enum names are persisted and never renamed. */
    val stringKey: String get() = "feature_${name.lowercase()}_summary"
}

/**
 * The single query point for tier gating. Free binds [FreeEntitlements]; premium binds a Play Billing backed
 * implementation. Nothing else should know which flavour it runs in.
 */
interface Entitlements {
    fun has(feature: Feature): Boolean
    /** Emits the current granted set and every change (so a purchase unlocks rows without restart). */
    val granted: Flow<Set<Feature>>
}

object FreeEntitlements : Entitlements {
    private val none = MutableStateFlow<Set<Feature>>(emptySet())
    override fun has(feature: Feature): Boolean = false
    override val granted: StateFlow<Set<Feature>> = none
}

/** Simple mutable implementation for tests and for the premium flavour before billing lands. */
class StaticEntitlements(initial: Set<Feature>) : Entitlements {
    private val state = MutableStateFlow(initial)
    override fun has(feature: Feature): Boolean = feature in state.value
    override val granted: StateFlow<Set<Feature>> = state
    fun set(features: Set<Feature>) { state.value = features }
}
