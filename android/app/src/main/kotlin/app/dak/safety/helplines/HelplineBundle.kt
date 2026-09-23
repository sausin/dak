package app.dak.safety.helplines

import app.dak.classify.BundleCodec
import app.dak.classify.BundleVerifier
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What tapping a helpline does. */
@Serializable
enum class HelplineAction {
    /** Opens the dialer with the number filled in (ACTION_DIAL): the user places the call. */
    @SerialName("call") CALL,

    /** Opens Dak's composer to the number (e.g. the TRAI 1909 complaint). */
    @SerialName("sms") SMS,

    /** Opens the official page in the browser. */
    @SerialName("url") URL,
}

/** Grouping used for ordering and icons on the Report fraud screen. */
@Serializable
enum class HelplineCategory {
    @SerialName("cybercrime") CYBERCRIME,
    @SerialName("fraud_communication") FRAUD_COMMUNICATION,
    @SerialName("spam") SPAM,
    @SerialName("emergency") EMERGENCY,
    @SerialName("banking") BANKING,
    @SerialName("bank_card_block") BANK_CARD_BLOCK,
}

/** One helpline (see shared/formats/helplines-v1.schema.json). */
@Serializable
data class Helpline(
    val id: String,
    val name: String,
    val country: String,
    val category: HelplineCategory,
    val action: HelplineAction,
    val target: String,
    val purpose: String,
    val sourceUrl: String,
    val lastVerified: String? = null,
    val needsVerification: Boolean = true,
    val verificationNote: String? = null,
    /** For [HelplineAction.SMS]: body template with `{text}`, `{sender}`, `{date:<pattern>}`. */
    val smsFormat: String? = null,
)

/** The signed part of the bundle. */
@Serializable
data class HelplinePayload(
    val format: String,
    val version: Int,
    val revision: Int,
    val issuedAt: String,
    val notes: String? = null,
    val helplines: List<Helpline> = emptyList(),
    val bankCardBlock: List<Helpline> = emptyList(),
)

@Serializable
data class SignedHelplineBundle(
    val payload: HelplinePayload,
    /** Base64 signature over the canonical JSON of [payload]; null for the copy bundled in the app. */
    val signature: String? = null,
)

/**
 * Parsing and verification of the helplines bundle. The bundled asset is trusted as is ([parseBundled]); an
 * over-the-air copy must verify with a [BundleVerifier] ([parseSigned]), exactly like the classifier's template
 * bundle, and fails closed.
 */
object HelplineBundle {
    const val FORMAT = "dak-helplines"
    const val VERSION = 1

    /** Asset path of the bundled copy (identical to shared/formats/helplines-v1.json). */
    const val ASSET_NAME = "helplines-v1.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Parses the bundled (trusted) copy; null only if it is malformed or of another format/version. */
    fun parseBundled(text: String): HelplinePayload? =
        decode(text)?.payload?.takeIf(::isSupported)

    /** Parses an OTA copy and verifies its signature; null if unsigned, malformed or not verified. */
    fun parseSigned(text: String, verifier: BundleVerifier): HelplinePayload? {
        val signed = decode(text) ?: return null
        val signature = signed.signature ?: return null
        if (!isSupported(signed.payload)) return null
        val canonical = canonicalPayload(signed.payload).toByteArray(Charsets.UTF_8)
        val ok = runCatching { verifier.verify(canonical, BundleCodec.decode(signature)) }.getOrDefault(false)
        return if (ok) signed.payload else null
    }

    /** The exact bytes an OTA signature covers (for signing tools and tests). */
    fun canonicalPayload(payload: HelplinePayload): String = json.encodeToString(HelplinePayload.serializer(), payload)

    private fun decode(text: String): SignedHelplineBundle? =
        runCatching { json.decodeFromString(SignedHelplineBundle.serializer(), text) }.getOrNull()

    private fun isSupported(payload: HelplinePayload): Boolean = payload.format == FORMAT && payload.version == VERSION
}
