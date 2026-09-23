package app.dak.classify

import app.dak.core.model.Category
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One known sender header entry in a template bundle. */
@Serializable
public data class SenderEntry(
    /** DLT entity header, e.g. "HDFCBK" ([SenderId.mergeKey] shape), or a plain merge key for others. */
    val header: String,
    val brand: String,
    val categoryHint: Category = Category.UNKNOWN,
    /** 0 (worst) .. 100 (best). Absent/unknown senders default to neutral (50) at call sites. */
    val reputation: Int = 50,
    /**
     * ISO 3166-1 alpha-2 countries this sender belongs to (e.g. `["IN"]` for Indian DLT headers). Empty (the
     * default, and what older bundles carry) means global. See [SenderRegion.matches].
     */
    val regions: List<String> = emptyList(),
)

/** A single deterministic classification rule. */
@Serializable
public data class TemplateRule(
    val id: String,
    val category: Category,
    /** Regex pattern, matched case-insensitively against the message body. */
    val pattern: String,
    /** If set, this rule only applies when the sender's merge-key header matches one of these. */
    val senderHeaders: List<String> = emptyList(),
    /** Higher priority rules are tried first / win ties. */
    val priority: Int = 0,
    /** Extra labels to attach to the [app.dak.core.model.Classification] when this rule fires. */
    val labels: Set<String> = emptySet(),
    /**
     * ISO 3166-1 alpha-2 countries this rule is written for (e.g. `["IN"]` for UPI/IMPS wording). Empty (the
     * default, and what older bundles carry) means the rule is generic and applies everywhere.
     */
    val regions: List<String> = emptyList(),
)

/** The payload of a template bundle: everything except the signature. */
@Serializable
public data class TemplatePayload(
    val version: Int,
    /** Epoch millis. */
    val issuedAt: Long,
    val senders: List<SenderEntry> = emptyList(),
    val rules: List<TemplateRule> = emptyList(),
)

/** A signed (or bundled-trusted) template bundle. */
@Serializable
public data class SignedTemplateBundle(
    val payload: TemplatePayload,
    /** Base64 signature over the canonical JSON encoding of [payload]; null for the bundled default. */
    val signature: String? = null,
)

/**
 * A loaded, ready-to-use template bundle: sender headers indexed by merge key, and rules sorted by
 * priority (highest first).
 */
public class TemplateBundle private constructor(
    public val version: Int,
    public val issuedAt: Long,
    private val sendersByHeader: Map<String, SenderEntry>,
    public val rules: List<TemplateRule>,
    /** Brand -> the first header listed for it in the bundle (the brand's stable fold key). */
    private val primaryHeaderByBrand: Map<String, String> = emptyMap(),
) {
    public fun sender(mergeKey: String): SenderEntry? = sendersByHeader[mergeKey.uppercase()]

    /**
     * [sender], but only if the entry belongs to [region] (an entry tagged `["IN"]` is ignored for a UK SIM, so a
     * British sender that happens to share an Indian header name is not given that brand). Unknown regions and
     * untagged entries always match.
     */
    public fun sender(mergeKey: String, region: SenderRegion): SenderEntry? =
        sender(mergeKey)?.takeIf { region.matches(it.regions) }

    /**
     * The brand-level fold key of a sender: the first header the bundle lists for the same brand (e.g. `HDFCBK`
     * for `HDFC`, `HDFCBK` or any other header of "HDFC Bank"), or null when the sender is unknown. Senders with
     * the same brand share one key, so a UI can fold them into one conversation; the key is a real header, so the
     * brand's main conversation keeps its id when more headers are added to the bundle.
     */
    public fun brandKey(mergeKey: String): String? {
        val brand = sender(mergeKey)?.brand ?: return null
        return primaryHeaderByBrand[brandNormal(brand)]
    }

    public fun rulesFor(mergeKey: String?): List<TemplateRule> =
        rules.filter { it.senderHeaders.isEmpty() || (mergeKey != null && mergeKey.uppercase() in it.senderHeaders) }

    /**
     * [rulesFor] limited to rules for [region]: generic (untagged) rules always apply; region-tagged rules apply only
     * in their regions (all of them when the region is unknown). Still highest priority first, and at equal priority
     * a rule written for this region is tried before a generic one.
     */
    public fun rulesFor(mergeKey: String?, region: SenderRegion): List<TemplateRule> =
        rulesFor(mergeKey)
            .filter { region.matches(it.regions) }
            .sortedWith(compareByDescending<TemplateRule> { it.priority }.thenBy { if (it.regions.isEmpty()) 1 else 0 })

    public companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Loads and parses the bundled default template resource. Always trusted, never signature-checked. */
        public fun loadDefault(): TemplateBundle {
            val stream = requireNotNull(TemplateBundle::class.java.getResourceAsStream("/app/dak/classify/default-templates.json")) {
                "default-templates.json resource missing"
            }
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val payload = json.decodeFromString(TemplatePayload.serializer(), text)
            return from(payload)
        }

        /**
         * Parses an OTA bundle's raw JSON (a [SignedTemplateBundle]) and verifies it with
         * [verifier]. Returns null if the signature is missing, malformed, or fails verification.
         */
        public fun parse(json: String, verifier: BundleVerifier): TemplateBundle? {
            val signed = try {
                this.json.decodeFromString(SignedTemplateBundle.serializer(), json)
            } catch (_: Exception) {
                return null
            }
            val signature = signed.signature ?: return null
            val canonicalPayload = this.json.encodeToString(TemplatePayload.serializer(), signed.payload)
            val ok = try {
                verifier.verify(canonicalPayload.toByteArray(Charsets.UTF_8), BundleCodec.decode(signature))
            } catch (_: Exception) {
                false
            }
            if (!ok) return null
            return from(signed.payload)
        }

        /** Parses a bundle payload JSON with no signature requirement (for tests/tools only). */
        public fun parseUnsigned(payloadJson: String): TemplateBundle =
            from(json.decodeFromString(TemplatePayload.serializer(), payloadJson))

        private fun from(payload: TemplatePayload): TemplateBundle = TemplateBundle(
            version = payload.version,
            issuedAt = payload.issuedAt,
            sendersByHeader = payload.senders.associateBy { it.header.uppercase() },
            rules = payload.rules.sortedByDescending { it.priority },
            primaryHeaderByBrand = LinkedHashMap<String, String>().apply {
                for (entry in payload.senders) {
                    if (entry.brand.isNotBlank()) putIfAbsent(brandNormal(entry.brand), entry.header.uppercase())
                }
            },
        )

        private fun brandNormal(brand: String): String = brand.trim().lowercase()
    }
}
