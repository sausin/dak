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
 * @param cacheSize entries of the template-hash result cache ([TemplateCache]); 0, the default, disables it. The
 *   cache only engages for a [NaiveBayesModel] and a bundle whose patterns are digit-blind, and results are identical
 *   either way. It is off by default because exactness forces a length-preserving key, which on realistic traffic
 *   hits only ~7% of messages and costs more than it saves once [prefilter] is on (docs/performance.md).
 * @param prefilter run a template rule's regex only when one of its keywords occurs in the body ([RulePrefilter]);
 *   results are identical either way.
 *
 * Thread-safe: one instance is shared by the notification path and the indexer (which classifies in parallel).
 */
public class ClassifierPipeline(
    private val templates: TemplateBundle,
    private val model: MessageModel,
    private val cloud: CloudClassifier = NoCloudClassifier,
    private val contactLookup: (String) -> Boolean = { false },
    private val threshold: Float = 0.55f,
    private val regionFor: (subId: Int) -> SenderRegion = { SenderRegion.UNKNOWN },
    cacheSize: Int = 0,
    prefilter: Boolean = true,
) {

    private val rulePrefilter: RulePrefilter? = if (prefilter) RulePrefilter(templates.rules) else null

    private val naiveBayes: NaiveBayesModel? = model as? NaiveBayesModel

    private val cache: TemplateCache? =
        if (cacheSize > 0 && naiveBayes != null && TemplateCache.isDigitBlind(templates.rules.map { it.pattern })) {
            TemplateCache(cacheSize)
        } else {
            null
        }

    /** Every header some rule is restricted to; any other sender gets the generic rule list. */
    private val restrictedHeaders: Set<String> = templates.rules.flatMapTo(HashSet()) { it.senderHeaders }

    /** `rulesFor(mergeKey, region)` per (region, rule group): the bundle is immutable, the group count is bounded. */
    private val rulesMemo = java.util.concurrent.ConcurrentHashMap<String, List<TemplateRule>>()

    /** Cache hits and misses so far (0/0 when the cache is off). For diagnostics and the benchmark. */
    internal val cacheCounters: Pair<Long, Long> get() = (cache?.hits ?: 0L) to (cache?.misses ?: 0L)

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
        val senderClass = senderClassOf(address)
        val links by lazy(LazyThreadSafetyMode.NONE) { LinkExtractor.extract(body) }
        if (isUnknownNumeric(address, region) && links.isNotEmpty()) labels += "unknown-sender-link"

        // Template-dependent stages (rules, model scores) may come from the template-hash cache; see TemplateCache.
        val ruleGroup = mergeKey.uppercase().takeIf { it in restrictedHeaders }
        val cacheKey = cache?.let { c ->
            val context = "${region.countryIso}|${ruleGroup ?: ""}|${senderEntry?.header?.uppercase() ?: ""}|" +
                "${dltHeader?.route ?: ""}|${senderClass.ordinal}"
            TemplateCache.keyOf(context, body) { naiveBayes!!.knows(it) }
        }
        var entry = cacheKey?.let { cache!!.get(it) }
        val templateResult = if (entry != null) {
            entry.template
        } else {
            matchTemplates(rulesFor(ruleGroup, region, senderClass), body, dltHeader)
        }
        if (cacheKey != null && entry == null) {
            entry = TemplateCache.Entry(templateResult)
            cache!!.put(cacheKey, entry)
        }
        // An OTP message carries a code: "the OTP will be shared by the agent" or "never share your OTP" is not one.
        // Decided per message (not cached: the extractor tells a code from a phone number by its digits).
        val otpCode by lazy(LazyThreadSafetyMode.NONE) { OtpExtractor.extract(body) }
        // Every code has a digit (any script), so digit-free bodies, most chat, skip the extractor.
        val hasCode by lazy(LazyThreadSafetyMode.NONE) {
            body.any { Character.isDigit(it) } && (CODE_LIKE.containsMatchIn(body) || otpCode != null)
        }
        var matched = templateResult
        if (matched?.category == Category.OTP && !hasCode) {
            matched = matchTemplates(rulesFor(ruleGroup, region, senderClass).filter { it.category != Category.OTP }, body, dltHeader)
        }
        var candidate: Classification = if (matched != null && matched.confidence >= threshold) {
            routeChecked(matched, dltHeader) { links }
        } else {
            modelStage(address, body, senderEntry, region, dltHeader, senderClass, hasCode, entry)
        }
        // A phone number nobody saved, sending a look-alike, suspicious-TLD or userinfo link: phishing, whatever the
        // wording (OTP messages excluded: the code is what the user needs to see).
        if (senderClass == SenderClass.UNKNOWN_NUMBER && candidate.category != Category.OTP && hasRiskyLink(links)) {
            candidate = Classification(
                category = Category.SPAM,
                confidence = maxOf(candidate.confidence, RISKY_LINK_CONFIDENCE),
                source = ClassifierSource.TEMPLATE,
                labels = candidate.labels + "fraud-risk",
            )
        }

        if (candidate.confidence < threshold) {
            val cloudResult = cloudStage(dltHeader, mergeKey, body)
            if (cloudResult != null) candidate = cloudResult
        }

        val finalCategory = if (candidate.confidence < threshold) Category.UNKNOWN else candidate.category
        labels += candidate.labels

        val otp = if (finalCategory == Category.OTP) otpCode else null

        return Classification(
            category = finalCategory,
            confidence = candidate.confidence,
            source = candidate.source,
            otp = otp,
            canonicalSender = canonicalSender,
            labels = labels,
        )
    }

    private fun rulesFor(ruleGroup: String?, region: SenderRegion, senderClass: SenderClass): List<TemplateRule> =
        rulesMemo.getOrPut("${region.countryIso}|${ruleGroup ?: ""}|${senderClass.ordinal}") {
            templates.rulesFor(ruleGroup, region).filter { rule -> ruleApplies(rule.senderScope, senderClass, region) }
        }

    /** Who sent a message, as far as [SenderScope] and the risky-link check care. */
    internal enum class SenderClass {
        /** A DLT header, alphanumeric name or short code (`121`, `56070`). */
        BUSINESS,

        /** A full-length phone number that is not a saved contact. */
        UNKNOWN_NUMBER,

        /** A full-length phone number saved as a contact (a short code saved as a contact is still a business). */
        CONTACT,
    }

    /** Contacts are only looked up for numeric senders (the lookup may hit the contacts provider). */
    private fun senderClassOf(address: String): SenderClass {
        val kind = SenderId.classify(address)
        val phone = kind == SenderKind.PHONE_NUMBER && address.count { it.isDigit() } >= PRIVATE_NUMBER_MIN_DIGITS
        return when {
            !phone -> SenderClass.BUSINESS
            contactLookup(address) -> SenderClass.CONTACT
            else -> SenderClass.UNKNOWN_NUMBER
        }
    }

    private fun ruleApplies(scope: SenderScope, senderClass: SenderClass, region: SenderRegion): Boolean = when (scope) {
        SenderScope.ANY -> true
        SenderScope.BUSINESS -> when (senderClass) {
            SenderClass.BUSINESS -> true
            SenderClass.CONTACT -> false
            SenderClass.UNKNOWN_NUMBER -> !region.dltSenderIds
        }
        SenderScope.PRIVATE_NUMBER -> senderClass == SenderClass.UNKNOWN_NUMBER && region.dltSenderIds
    }

    /**
     * DLT routes are registered per brand and per template, so the traffic type bounds what a message can be:
     * - a spam verdict on a registered route (`-P`, `-S`, `-T`, `-G`) stands only with a risky link (look-alike,
     *   suspicious TLD, shortener, IDN or userinfo); otherwise it is the brand's own notice ("update your KYC at the
     *   branch", "your card has been blocked as requested"): a promotion on `-P`, an update elsewhere;
     * - `-P` carries marketing only, so a transaction-looking promotion ("Rs 500 cashback credited*") is a promotion.
     */
    private fun routeChecked(result: Classification, dltHeader: DltHeader?, links: () -> List<ExtractedLink>): Classification {
        val route = dltHeader?.route ?: return result
        return when {
            result.category == Category.SPAM && !hasRiskyLink(links(), includeShortened = true) -> result.copy(
                category = if (route == TrafficType.PROMOTIONAL) Category.PROMOTION else Category.TRANSACTION,
                labels = result.labels - "fraud-risk",
            )
            result.category == Category.TRANSACTION && route == TrafficType.PROMOTIONAL -> result.copy(category = Category.PROMOTION)
            else -> result
        }
    }

    private fun hasRiskyLink(links: List<ExtractedLink>, includeShortened: Boolean = false): Boolean = links.any { link ->
        when (linkChecker.check(link).risk) {
            LinkRisk.LOOKALIKE, LinkRisk.SUSPICIOUS_TLD -> true
            LinkRisk.SHORTENED -> includeShortened
            LinkRisk.OFFICIAL, LinkRisk.UNKNOWN -> false
        }
    }

    private val linkChecker = LookalikeDomainChecker()

    private fun matchTemplates(
        rules: List<TemplateRule>,
        body: String,
        dltHeader: DltHeader?,
    ): Classification? {
        val hits = if (rules.isEmpty()) null else rulePrefilter?.scan(body)
        for (rule in rules) {
            if (hits != null && !rulePrefilter!!.mayMatch(rule, hits)) continue
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
        // No regex rule fired, but the DLT route still counts as a deterministic, if weaker, signal: `-P` is registered
        // for marketing only, `-T` for transactions and `-G` for government notices. (`-S` service templates also
        // carry consented promotions, so they are left to the model.) The route, not the brand: a sender-table
        // category hint is never used, so a brand-new sender is categorised exactly like a known one.
        val hint = when (dltHeader?.route) {
            TrafficType.PROMOTIONAL -> Category.PROMOTION
            TrafficType.TRANSACTIONAL, TrafficType.GOVERNMENT -> Category.TRANSACTION
            TrafficType.SERVICE_IMPLICIT, null -> null
        }
        if (hint != null) {
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

        /** A sensible `cacheSize` when the template-hash cache is enabled (entries of at most 640 chars each). */
        public const val SUGGESTED_CACHE_SIZE: Int = 2_048

        /** Model-score factor for categories a DLT route should not carry (promotions on `-T`, spam on `-S`/`-T`). */
        private const val DLT_ROUTE_DAMPING: Float = 0.4f

        /** Digits from which a numeric sender is a person's phone number rather than a short code (`121`, `56070`). */
        private const val PRIVATE_NUMBER_MIN_DIGITS: Int = 8

        /**
         * A standalone 4-8 digit number (not part of an amount, date, phone number or reference): with an OTP keyword,
         * enough to call a message an OTP even when [OtpExtractor] cannot tell which number is the code.
         */
        private val CODE_LIKE = Regex("""(?<![\w.,/:-])\d{4,8}(?![\w,/:-]|\.\d)""")

        /** Confidence of "a saved contact's message no rule flagged is personal". */
        private const val CONTACT_CONFIDENCE: Float = 0.8f

        /** Confidence of the "unknown number sending a risky link" verdict. */
        private const val RISKY_LINK_CONFIDENCE: Float = 0.9f
    }

    private fun trafficTypeLabel(dltHeader: DltHeader?): Set<String> = when (dltHeader?.route) {
        TrafficType.PROMOTIONAL -> setOf("dlt-promotional")
        TrafficType.SERVICE_IMPLICIT -> setOf("dlt-service")
        TrafficType.TRANSACTIONAL -> setOf("dlt-transactional")
        TrafficType.GOVERNMENT -> setOf("dlt-government")
        null -> emptySet()
    }

    private fun modelStage(
        address: String,
        body: String,
        senderEntry: SenderEntry?,
        region: SenderRegion,
        dltHeader: DltHeader?,
        senderClass: SenderClass,
        hasOtpCode: Boolean,
        cached: TemplateCache.Entry?,
    ): Classification {
        val raw = cached?.modelScores ?: model.predict(body).also { cached?.modelScores = it }
        // A saved contact is a person: anything the rules did not flag (spam, an OTP, a call alert) is personal,
        // whatever the model's small vocabulary makes of "sent you Rs 500" or "the parcel was delivered".
        if (senderClass == SenderClass.CONTACT) {
            return Classification(
                category = Category.PERSONAL,
                confidence = maxOf(raw[Category.PERSONAL] ?: 0f, CONTACT_CONFIDENCE),
                source = ClassifierSource.MODEL,
                canonicalSender = senderEntry?.brand,
            )
        }
        val scores = raw.toMutableMap()
        if (isPersonalLikely(address, region)) {
            val boosted = (scores[Category.PERSONAL] ?: 0f) * 1.6f + 0.1f
            scores[Category.PERSONAL] = boosted
            normalize(scores)
        }
        // DLT routes are registered per template: `-T` carries no marketing at all and `-S` is a registered
        // service template (service-explicit promos use it too, so only spam is damped there). The model's
        // small vocabulary otherwise reads "rate our service" / "click for feedback" as promo or spam.
        // `-G` is a government department's registered header: neither marketing nor spam; `-P` carries marketing
        // only. And no DLT header is a person (carrier call alerts are caught by the templates).
        when (dltHeader?.route) {
            TrafficType.TRANSACTIONAL, TrafficType.GOVERNMENT -> damp(scores, Category.PROMOTION, Category.SPAM)
            TrafficType.SERVICE_IMPLICIT -> damp(scores, Category.SPAM)
            TrafficType.PROMOTIONAL -> damp(scores, Category.TRANSACTION, Category.OTP)
            null -> Unit
        }
        if (dltHeader != null) damp(scores, Category.PERSONAL)
        // Where businesses cannot send from one, a private number is a person (or a scammer, which the spam rules and
        // the risky-link check catch): not an alert and not an ad.
        if (senderClass == SenderClass.UNKNOWN_NUMBER && region.dltSenderIds) damp(scores, Category.TRANSACTION, Category.PROMOTION)
        // No code, no OTP message.
        if (!hasOtpCode) damp(scores, Category.OTP)
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

    private fun damp(scores: MutableMap<Category, Float>, vararg categories: Category) {
        for (c in categories) scores[c]?.let { scores[c] = it * DLT_ROUTE_DAMPING }
        normalize(scores)
    }

    private fun normalize(scores: MutableMap<Category, Float>) {
        val sum = scores.values.sum().coerceAtLeast(1e-6f)
        scores.keys.toList().forEach { scores[it] = scores.getValue(it) / sum }
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
     * A phone-number sender (or, where banks never use them, a short code) that is not a contact: with a link, it gets
     * the "unknown-sender-link" label. In the US, UK and most markets outside India banks and services send from
     * short codes, so those are not flagged.
     */
    private fun isUnknownNumeric(address: String, region: SenderRegion): Boolean {
        val kind = SenderId.classify(address)
        val numeric = kind == SenderKind.PHONE_NUMBER || (kind == SenderKind.SHORT_CODE && region.shortCodesSuspicious)
        return numeric && !contactLookup(address)
    }
}
