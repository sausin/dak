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
 */
public class ClassifierPipeline(
    private val templates: TemplateBundle,
    private val model: MessageModel,
    private val cloud: CloudClassifier = NoCloudClassifier,
    private val contactLookup: (String) -> Boolean = { false },
    private val threshold: Float = 0.55f,
) {

    /** Classifies one message. [subId] is accepted for future SIM-scoped rules; unused today. */
    public suspend fun classify(address: String, body: String, @Suppress("UNUSED_PARAMETER") subId: Int): Classification {
        val dltHeader = SenderId.parseDltHeader(address)
        val mergeKey = SenderId.mergeKey(address)
        val senderEntry = templates.sender(mergeKey)
        val canonicalSender = senderEntry?.brand

        var labels = mutableSetOf<String>()
        if (isUnknownSenderWithLink(address, body)) labels += "unknown-sender-link"

        val templateResult = matchTemplates(mergeKey, body, dltHeader)
        var candidate: Classification = if (templateResult != null && templateResult.confidence >= threshold) {
            templateResult
        } else {
            modelStage(address, body, senderEntry)
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

    private fun matchTemplates(mergeKey: String, body: String, dltHeader: DltHeader?): Classification? {
        val rules = templates.rulesFor(mergeKey)
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
        val hint = templates.sender(mergeKey)?.categoryHint
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

    private fun trafficTypeLabel(dltHeader: DltHeader?): Set<String> = when (dltHeader?.trafficType) {
        TrafficType.PROMOTIONAL -> setOf("dlt-promotional")
        TrafficType.SERVICE_IMPLICIT -> setOf("dlt-service")
        TrafficType.TRANSACTIONAL -> setOf("dlt-transactional")
        TrafficType.GOVERNMENT -> setOf("dlt-government")
        null -> emptySet()
    }

    private fun modelStage(address: String, body: String, senderEntry: SenderEntry?): Classification {
        val scores = model.predict(body).toMutableMap()
        if (isPersonalLikely(address, body)) {
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

    private fun isPersonalLikely(address: String, body: String): Boolean {
        if (contactLookup(address)) return true
        return SenderId.isIndianMobile(address)
    }

    private fun isUnknownSenderWithLink(address: String, body: String): Boolean {
        val kind = SenderId.classify(address)
        val numericUnknown = (kind == SenderKind.PHONE_NUMBER || kind == SenderKind.SHORT_CODE) && !contactLookup(address)
        return numericUnknown && LinkExtractor.extract(body).isNotEmpty()
    }
}
