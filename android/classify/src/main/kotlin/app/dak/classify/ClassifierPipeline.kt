package app.dak.classify

import app.dak.core.model.Category
import app.dak.core.model.Classification
import app.dak.core.model.ClassifierSource

/**
 * The three-stage classification pipeline described in the build plan:
 *
 * 1. Deterministic templates (signed JSON bundle of DLT sender headers + regex rules).
 * 2. An on-device model ([MessageModel]) for everything the templates don't confidently resolve.
 * 3. An opt-in cloud classifier ([CloudClassifier]), used only when stage 1/2 confidence is below
 *    [threshold]; below threshold even after that, the result is [Category.UNKNOWN] rather than a
 *    guess.
 *
 * @param contactLookup returns true if [address] is a saved contact.
 * @param regionFor the sender conventions for a message received on SIM `subId` (normally that SIM's home
 *   country, see [SenderRegion]). DLT-specific handling (traffic-type labels, header trust) and region-tagged
 *   template entries follow it; the default, [SenderRegion.UNKNOWN], applies generic rules only.
 */
public class ClassifierPipeline(
    private val templates: TemplateBundle,
    private val model: MessageModel,
    private val cloud: CloudClassifier = NoCloudClassifier,
    private val contactLookup: (String) -> Boolean = { false },
    private val threshold: Float = 0.55f,
    private val regionFor: (subId: Int) -> SenderRegion = { SenderRegion.UNKNOWN },
) {

    /** Classifies one message received on SIM [subId] (whose region picks the sender conventions). */
    public suspend fun classify(address: String, body: String, subId: Int): Classification =
        classifyBounded(
            address,
            if (body.length > MAX_CLASSIFY_CHARS) body.substring(0, MAX_CLASSIFY_CHARS) else body,
            runCatching { regionFor(subId) }.getOrDefault(SenderRegion.UNKNOWN),
        )

    private suspend fun classifyBounded(address: String, body: String, region: SenderRegion): Classification {
        // Only the head of a message is classified: templates, the model and OTP extraction all key off the first
        // few hundred characters, and bounding the input bounds the worst case of every regex run on it (MMS text
        // parts can be megabytes of sender-chosen text).
        // DLT headers only exist on Indian networks; elsewhere a header-shaped name is just an alphanumeric sender.
        val dltHeader = if (region.dltSenderIds) SenderId.parseDltHeader(address) else null
        val mergeKey = SenderId.mergeKey(address)
        val senderEntry = templates.sender(mergeKey, region)
        val canonicalSender = senderEntry?.brand

        var labels = mutableSetOf<String>()
        if (isUnknownSenderWithLink(address, body, region)) labels += "unknown-sender-link"

        val templateResult = matchTemplates(mergeKey, body, dltHeader, senderEntry, region)
        var candidate: Classification = if (templateResult != null && templateResult.confidence >= threshold) {
            templateResult
        } else {
            modelStage(address, body, senderEntry, region)
        }

        if (candidate.confidence < threshold) {
            val cloudResult = cloudStage(dltHeader, mergeKey, body)
            if (cloudResult != null) candidate = cloudResult
        }

        val finalCategory = if (candidate.confidence < threshold) Category.UNKNOWN else candidate.category
        labels += candidate.labels

        val otp = if (finalCategory == Category.OTP) OtpExtractor.extract(body) else null

        return Classification(
            category = finalCategory,
            confidence = candidate.confidence,
            source = candidate.source,
            otp = otp,
            canonicalSender = canonicalSender,
            labels = labels,
        )
    }

    private fun matchTemplates(
        mergeKey: String,
        body: String,
        dltHeader: DltHeader?,
        senderEntry: SenderEntry?,
        region: SenderRegion,
    ): Classification? {
        val rules = templates.rulesFor(mergeKey, region)
        for (rule in rules) {
            val regex = ruleRegex(rule) ?: continue
            if (regex.containsMatchIn(body)) {
                return Classification(
                    category = rule.category,
                    confidence = 0.97f,
                    source = ClassifierSource.TEMPLATE,
                    labels = rule.labels + trafficTypeLabel(dltHeader),
                )
            }
        }
        // No regex rule fired, but a known sender with a strong category hint (e.g. a promo-only
        // telecom header) still counts as a deterministic, if weaker, signal.
        val hint = senderEntry?.categoryHint
        if (hint != null && hint != Category.UNKNOWN) {
            return Classification(
                category = hint,
                confidence = 0.6f,
                source = ClassifierSource.TEMPLATE,
                labels = trafficTypeLabel(dltHeader),
            )
        }
        return null
    }

    // Thread-safe: the pipeline is shared by the notification path and the indexer.
    private val ruleRegexCache = java.util.concurrent.ConcurrentHashMap<String, Result<Regex>>()

    private fun ruleRegex(rule: TemplateRule): Regex? = ruleRegexCache.getOrPut(rule.id) {
        runCatching { Regex(rule.pattern, setOf(RegexOption.IGNORE_CASE)) }
    }.getOrNull()

    public companion object {
        /** Characters of a body the pipeline looks at. */
        public const val MAX_CLASSIFY_CHARS: Int = 4_000
    }

    private fun trafficTypeLabel(dltHeader: DltHeader?): Set<String> = when (dltHeader?.trafficType) {
        TrafficType.PROMOTIONAL -> setOf("dlt-promotional")
        TrafficType.SERVICE_IMPLICIT -> setOf("dlt-service")
        TrafficType.TRANSACTIONAL -> setOf("dlt-transactional")
        TrafficType.GOVERNMENT -> setOf("dlt-government")
        null -> emptySet()
    }

    private fun modelStage(address: String, body: String, senderEntry: SenderEntry?, region: SenderRegion): Classification {
        val scores = model.predict(body).toMutableMap()
        if (isPersonalLikely(address, region)) {
            val boosted = (scores[Category.PERSONAL] ?: 0f) * 1.6f + 0.1f
            scores[Category.PERSONAL] = boosted
            val sum = scores.values.sum().coerceAtLeast(1e-6f)
            scores.keys.toList().forEach { scores[it] = scores.getValue(it) / sum }
        }
        val best = scores.maxByOrNull { it.value }
        val category = best?.key ?: Category.UNKNOWN
        val confidence = best?.value ?: 0f
        return Classification(
            category = category,
            confidence = confidence,
            source = ClassifierSource.MODEL,
            canonicalSender = senderEntry?.brand,
        )
    }

    private suspend fun cloudStage(dltHeader: DltHeader?, mergeKey: String, body: String): Classification? {
        val header = dltHeader?.raw() ?: mergeKey
        val masked = Masker.mask(body)
        val verdict = cloud.classify(header, masked) ?: return null
        return Classification(
            category = verdict.category,
            confidence = verdict.probability,
            source = ClassifierSource.CLOUD,
        )
    }

    private fun isPersonalLikely(address: String, region: SenderRegion): Boolean {
        if (contactLookup(address)) return true
        return region.isLocalMobile(address)
    }

    /**
     * A phone-number sender (or, where banks never use them, a short code) that is not a contact and sends a link.
     * In the US, UK and most markets outside India banks and services send from short codes, so those are not flagged.
     */
    private fun isUnknownSenderWithLink(address: String, body: String, region: SenderRegion): Boolean {
        val kind = SenderId.classify(address)
        val numeric = kind == SenderKind.PHONE_NUMBER || (kind == SenderKind.SHORT_CODE && region.shortCodesSuspicious)
        val numericUnknown = numeric && !contactLookup(address)
        return numericUnknown && LinkExtractor.extract(body).isNotEmpty()
    }
}
