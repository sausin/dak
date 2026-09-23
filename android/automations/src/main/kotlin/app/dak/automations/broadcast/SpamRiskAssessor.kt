package app.dak.automations.broadcast

/** Why a broadcast looks risky. */
public enum class SpamSignal {
    /** The text contains a link or a bare domain. */
    LINK,

    /** Offer / discount / sale / "click" / "limited time" style wording (English, Hindi, Hinglish). */
    PROMO_WORDS,

    /** Money bait: "% off", "cashback", "earn ₹", "win", lottery / prize / loan wording. */
    MONEY_BAIT,

    /** A call to action to phone or message a number ("call now", "WhatsApp 98...", "missed call"). */
    CALL_TO_ACTION,

    /** Shouting: most letters in capitals. */
    ALL_CAPS,

    /** Many recipients at once (at least [SpamRiskAssessor.LARGE_LIST] people). */
    LARGE_LIST,
}

/** How strongly the confirmation should warn. */
public enum class SpamRiskLevel { LOW, ELEVATED, HIGH }

/** The assessment of one broadcast. */
public data class SpamRisk(val level: SpamRiskLevel, val signals: Set<SpamSignal>) {
    /** Flagged or large sends need an explicit "These people expect this message" confirmation. */
    val requiresExtraConfirmation: Boolean get() = level != SpamRiskLevel.LOW

    /** The content itself looks promotional (not just a big list). */
    val looksPromotional: Boolean get() = signals.any { it != SpamSignal.LARGE_LIST }
}

/**
 * Flags broadcasts that look like unsolicited commercial messages, which operators throttle and regulators (India:
 * TRAI TCCCPR 2018) penalise when sent from a personal number. Pure word / pattern heuristics, on-device, no network.
 * Deliberately conservative in what it calls HIGH: a link plus offer wording, or several promotional signals.
 */
public object SpamRiskAssessor {

    /** Recipients from which a broadcast counts as a large send. */
    public const val LARGE_LIST: Int = 20

    public fun assess(text: String, recipientCount: Int): SpamRisk {
        val signals = LinkedHashSet<SpamSignal>()
        val lower = text.lowercase()
        if (LINK.containsMatchIn(lower)) signals += SpamSignal.LINK
        if (PROMO_LATIN.containsMatchIn(lower) || PROMO_DEVANAGARI.any { it in text }) signals += SpamSignal.PROMO_WORDS
        if (MONEY_LATIN.containsMatchIn(lower) || MONEY_DEVANAGARI.any { it in text }) signals += SpamSignal.MONEY_BAIT
        if (CTA.containsMatchIn(lower) || CTA_DEVANAGARI.any { it in text }) signals += SpamSignal.CALL_TO_ACTION
        if (isShouting(text)) signals += SpamSignal.ALL_CAPS
        if (recipientCount >= LARGE_LIST) signals += SpamSignal.LARGE_LIST

        val content = signals - SpamSignal.LARGE_LIST
        val offerish = SpamSignal.PROMO_WORDS in content || SpamSignal.MONEY_BAIT in content
        val level = when {
            SpamSignal.LINK in content && offerish -> SpamRiskLevel.HIGH
            content.size >= 2 -> SpamRiskLevel.HIGH
            content.isNotEmpty() && SpamSignal.LARGE_LIST in signals -> SpamRiskLevel.HIGH
            signals.isNotEmpty() -> SpamRiskLevel.ELEVATED
            else -> SpamRiskLevel.LOW
        }
        return SpamRisk(level, signals)
    }

    private fun isShouting(text: String): Boolean {
        val letters = text.filter { it.isLetter() && (it.isUpperCase() || it.isLowerCase()) }
        if (letters.length < 12) return false
        return letters.count { it.isUpperCase() } * 10 >= letters.length * 8
    }

    private fun words(vararg w: String): Regex =
        Regex("(?<![\\p{L}\\p{N}])(?:" + w.joinToString("|") + ")(?![\\p{L}\\p{N}])")

    private val LINK = Regex(
        "https?://|www\\.|(?<![\\p{L}\\p{N}@])[a-z0-9-]{2,}\\.(?:com|in|co|net|org|io|ly|me|app|shop|store|xyz|link|site|online|info|biz|to|gl|gy|page)(?![\\p{L}\\p{N}])",
    )

    private val PROMO_LATIN = words(
        "offers?", "discounts?", "sale", "mega sale", "click", "click here", "tap here", "limited time", "limited period",
        "hurry", "buy now", "shop now", "order now", "book now", "register now", "subscribe", "best deals?", "hot deals?",
        "deal of the day", "coupons?", "promo(?: code)?", "exclusive offer", "last chance", "today only", "don'?t miss",
        "free (?:gift|delivery|trial|recharge|shipping|demo)", "100% free", "flat \\d+",
        // Hinglish
        "chhoot", "chhut", "muft", "jaldi karein", "jaldi karo", "abhi kharid\\w*", "sirf aaj", "offer ka labh",
        "labh uthaye\\w*", "sasta", "saste",
    )
    private val PROMO_DEVANAGARI = listOf(
        "छूट", "ऑफर", "आफर", "सेल", "मुफ्त", "मुफ़्त", "क्लिक", "सीमित समय", "अभी खरीदें", "जल्दी करें", "सिर्फ आज", "सस्ता",
    )

    private val MONEY_LATIN = Regex(
        "\\d+\\s*%\\s*(?:off|discount|cashback)|cash ?back|(?<![\\p{L}])(?:winner|you(?: have)? won|win (?:a|an|big|cash|rs|₹)|" +
            "prizes?|lottery|jackpot|lucky draw|earn (?:rs|₹|\\d|money|up ?to|daily)|loans?|pre-?approved|double your|" +
            "kamao|kamaye|jeeto|jeetein|inaam)(?![\\p{L}])",
    )
    private val MONEY_DEVANAGARI = listOf("% छूट", "इनाम", "जीतें", "जीतो", "लॉटरी", "कैशबैक", "लोन", "कमाएं", "कमाओ", "पुरस्कार")

    private val CTA = Regex(
        "(?<![\\p{L}])(?:call now|call us|missed call|give a missed call|whatsapp (?:us|now|on)|sms \\w+ to \\d+|reply yes|" +
            "abhi call|call karein|call kare)(?![\\p{L}])",
    )
    private val CTA_DEVANAGARI = listOf("अभी कॉल", "मिस्ड कॉल", "कॉल करें")
}
