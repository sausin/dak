package app.dak.premium.consent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Every path by which Dak itself could move message content or message metadata off the phone to a server. Each one
 * is off by default and needs its own prominent disclosure plus an explicit "Allow" before first use (Google Play
 * User Data policy; DPDP Act 2023 s.6 / GDPR Art. 6(1)(a) consent). See `docs/privacy-compliance.md`.
 *
 * Not listed on purpose (see the privacy policy for why): ordinary SMS/MMS sending through the carrier, forwarding to
 * a person the user picked (SMS or WhatsApp one-tap), the TRAI 1909 report the user reviews and sends, exports and
 * encrypted backups written to a location the user picked. Those are user-directed transfers that never reach Dak.
 *
 * [id] is persisted in consent records: never rename one.
 */
enum class DataFlow(val id: String) {
    /** Jev: the sender ID and a masked copy of an unclear message go to a cloud classifier for a second opinion. */
    CLOUD_CLASSIFICATION("cloud_classification"),

    /** Automation webhooks: a rule posts the sender and message text to a web address the user entered. */
    WEBHOOKS("webhooks"),

    /** Premium relay: messages are relayed (end-to-end encrypted) through Dak's server to a paired web client. */
    WEB_RELAY("web_relay"),

    /** AI-assisted search: the typed search question (never message content) goes to a language model. */
    AI_SEARCH("ai_search"),
    ;

    companion object {
        fun byId(id: String): DataFlow? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The text of one prominent disclosure, in one [language]. [version] must be bumped whenever the meaning of any field
 * changes: a consent given to an older version no longer counts (see [ConsentLedger.isGranted]), so the user sees the
 * new text first. All translations of one version say the same thing; each has its own [textHash].
 */
data class Disclosure(
    val flow: DataFlow,
    val version: Int,
    val title: String,
    /** One line per item of data that leaves the phone. */
    val whatIsSent: List<String>,
    /** What stays on the phone even with this on (reassures, and makes the limit explicit). */
    val whatIsNotSent: List<String>,
    val sentTo: String,
    val why: String,
    val whenSent: String,
    val retention: String,
    val howToTurnOff: String,
    /** Language of the text (a BCP 47 language subtag such as `en` or `hi`); part of [canonicalText]. */
    val language: String = Disclosures.SOURCE_LANGUAGE,
) {
    /** The exact text shown, in a stable order, with its language; its hash goes into every consent record. */
    fun canonicalText(): String = buildString {
        append(flow.id).append('\n').append(version).append('\n')
        append("lang: ").append(language).append('\n')
        append(title).append('\n')
        whatIsSent.forEach { append("sent: ").append(it).append('\n') }
        whatIsNotSent.forEach { append("not sent: ").append(it).append('\n') }
        append("to: ").append(sentTo).append('\n')
        append("why: ").append(why).append('\n')
        append("when: ").append(whenSent).append('\n')
        append("retention: ").append(retention).append('\n')
        append("turn off: ").append(howToTurnOff)
    }

    /** Lower-case hex SHA-256 of [canonicalText]. */
    val textHash: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(canonicalText().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}

/**
 * The current disclosure for each [DataFlow]. Plain language, no legalese; the same facts as `docs/privacy-policy.md`.
 * Server-side promises here (retention, no training, no sale) are requirements on the premium backend, which does not
 * exist yet: `docs/privacy-compliance.md` tracks them.
 *
 * The English text below is the source. Translations live in `disclosures-<lang>.json` next to this class (Java
 * resources, so the hash is computed here and in JVM tests exactly as on the phone); a translation is used only when
 * it was made for the current [Disclosure.version], otherwise the English text is shown. Translations are legal text:
 * they need a reviewed translation (docs/i18n.md), and changing one changes its hash, so consents given in that
 * language are asked again.
 */
object Disclosures {
    /** Language of the source text in this file. */
    const val SOURCE_LANGUAGE: String = "en"

    /** Languages with a bundled translation (`disclosures-<lang>.json`). */
    val TRANSLATED_LANGUAGES: List<String> = listOf("hi", "es", "fr")

    private const val TURN_OFF = "Settings → Privacy → Data that leaves your phone. Turning it off stops it at once."

    val cloudClassification = Disclosure(
        flow = DataFlow.CLOUD_CLASSIFICATION,
        version = 3,
        title = "Send unclear messages for a second opinion (Jev)",
        whatIsSent = listOf(
            "The sender ID of a business message Dak could not sort on its own, such as \"VM-HDFCBK\" or a short code.",
            "A masked copy of that message: numbers, amounts, card numbers, links, email addresses and likely names are replaced with placeholders such as <NUM> before it leaves the phone.",
        ),
        whatIsNotSent = listOf(
            "OTP codes, amounts, account numbers and links (they are masked).",
            "Anything from a person: messages from phone numbers, and those numbers, never leave the phone.",
            "Messages Dak can already sort on the phone, your contacts, and your other messages.",
        ),
        sentTo = "Dak's classification service (run by Dak or a processor working for Dak), over an encrypted connection.",
        why = "To file the message under the right category (for example Transactions or Spam) when the on-device classifier is not sure.",
        whenSent = "Only for single new incoming messages the phone cannot classify confidently, up to your monthly limit (Settings → Categories and spam → Advanced).",
        retention = "The service returns a category and does not keep the text. It is not used to train models, for advertising, or sold.",
        howToTurnOff = TURN_OFF,
    )

    val webhooks = Disclosure(
        flow = DataFlow.WEBHOOKS,
        version = 2,
        title = "Send messages to your own web address (webhooks)",
        whatIsSent = listOf(
            "For each message that matches a rule with a webhook: the sender (address or sender ID), an internal message ID, and the text your rule's template produces (by default the whole message, which can include OTPs).",
        ),
        whatIsNotSent = listOf(
            "Messages that do not match one of your webhook rules.",
            "The webhook's signing secret (only a signature made with it is sent).",
        ),
        sentTo = "The web address you type into the rule (your own server, or a service such as a Slack or Telegram bot). Dak does not read it.",
        why = "So your rule can pass the message to a system you control.",
        whenSent = "Automatically, each time a matching message arrives, while the rule is on.",
        retention = "Whoever runs that web address decides how long they keep it. Dak keeps a log of each send on this phone (Automations → history).",
        howToTurnOff = "$TURN_OFF You can also turn off or delete the rule in Automations.",
    )

    val webRelay = Disclosure(
        flow = DataFlow.WEB_RELAY,
        version = 2,
        title = "Read and reply from your computer (relay)",
        whatIsSent = listOf(
            "Messages you choose to relay, encrypted on this phone with a key shared only with your paired computer (set up by scanning a QR code).",
            "Delivery details Dak's relay needs to route them: a pairing ID, the size and time of each encrypted item.",
        ),
        whatIsNotSent = listOf(
            "The readable text: Dak's relay server only ever holds encrypted data it cannot open.",
        ),
        sentTo = "Dak's relay server, which passes the encrypted data to your paired web client.",
        why = "So you can read and reply to messages from a computer.",
        whenSent = "While a device is paired and relaying is on.",
        retention = "Encrypted items are deleted from the relay once delivered, and after 7 days at most if never delivered.",
        howToTurnOff = "$TURN_OFF Unpairing the computer also stops it.",
    )

    val aiSearch = Disclosure(
        flow = DataFlow.AI_SEARCH,
        version = 2,
        title = "Ask questions about your messages in plain language (AI search)",
        whatIsSent = listOf(
            "Only the question you type in search, for example \"how much did I spend on Swiggy last month\".",
        ),
        whatIsNotSent = listOf(
            "Your messages. The service turns your question into search filters; the search itself runs on this phone.",
        ),
        sentTo = "A language-model service used by Dak to understand the question, over an encrypted connection.",
        why = "To turn your question into filters (sender, date, amount) that Dak then applies on the phone.",
        whenSent = "Each time you run a plain-language search while this is on.",
        retention = "The question is not kept after the answer is returned and is not used for training or advertising.",
        howToTurnOff = "$TURN_OFF Search then uses keywords and filters only.",
    )

    /** The current disclosures in the source language (English). */
    val all: List<Disclosure> = listOf(cloudClassification, webhooks, webRelay, aiSearch)

    /** The current disclosure for [flow] in the source language (English). */
    fun forFlow(flow: DataFlow): Disclosure = all.first { it.flow == flow }

    /**
     * The current disclosure for [flow] as shown to someone using [language] (a BCP 47 tag such as `hi` or `fr-CA`):
     * its translation when one exists for the current version, else the English text.
     */
    fun forFlow(flow: DataFlow, language: String): Disclosure = translation(flow, language) ?: forFlow(flow)

    /** The translation of the current disclosure for [flow] into [language], or null (none, or made for another version). */
    fun translation(flow: DataFlow, language: String): Disclosure? {
        val lang = normalizeLanguage(language)
        if (lang == SOURCE_LANGUAGE) return null
        val source = forFlow(flow)
        return translations(lang)[flow]?.takeIf { it.version == source.version }
    }

    /** `fr-CA` → `fr`, `hi_IN` → `hi`: disclosures are translated per language, not per region. */
    fun normalizeLanguage(tag: String): String = tag.trim().substringBefore('-').substringBefore('_').lowercase().ifEmpty { SOURCE_LANGUAGE }

    /**
     * Texts shown by earlier builds that say exactly what the current version says, so a consent given to them still
     * counts. Version 3 (Jev) and version 2 (the others) only added the language to [Disclosure.canonicalText] (for
     * translations); the English wording is unchanged. An entry applies only while the flow's current version is
     * [EquivalentText.sameAsVersion]: the next version bump (a change of meaning) retires it by itself.
     */
    internal val EQUIVALENT_EARLIER: List<EquivalentText> = listOf(
        EquivalentText(DataFlow.CLOUD_CLASSIFICATION, 2, "2e31a1655dbe17dedc9fd51006e00d4184d5bc64bfbcfb1df7d1074567c9cf23", sameAsVersion = 3),
        EquivalentText(DataFlow.WEBHOOKS, 1, "79631b8a562a8b42babad5e3b0980ff666932a16d7ca75c41188bfe4ac2afc35", sameAsVersion = 2),
        EquivalentText(DataFlow.WEB_RELAY, 1, "bbeac96d43b8e5dfae0ef34473ddad3813f505605dfdd9e6dad4c502b1d74b6d", sameAsVersion = 2),
        EquivalentText(DataFlow.AI_SEARCH, 1, "5c0bf2f65a5afcb5a42080b2d3c93479a8709443fe3ca6eef8bcd0864dd0f3ad", sameAsVersion = 2),
    )

    /** True when ([version], [hash]) is an earlier text of [flow] with the same meaning as its [currentVersion]. */
    internal fun isEquivalentEarlier(flow: DataFlow, version: Int, hash: String, currentVersion: Int): Boolean =
        EQUIVALENT_EARLIER.any { it.flow == flow && it.version == version && it.hash == hash && it.sameAsVersion == currentVersion }

    private val cache = ConcurrentHashMap<String, Map<DataFlow, Disclosure>>()
    private val json = Json { ignoreUnknownKeys = true }

    /** The bundled translations into [lang] by flow; empty (English is shown) when missing or unreadable. */
    internal fun translations(lang: String): Map<DataFlow, Disclosure> = cache.getOrPut(lang) {
        if (lang !in TRANSLATED_LANGUAGES) return@getOrPut emptyMap()
        runCatching<Map<DataFlow, Disclosure>> {
            val stream = Disclosures::class.java.getResourceAsStream("/app/dak/premium/consent/disclosures-$lang.json")
                ?: return@runCatching emptyMap()
            val file = stream.bufferedReader(Charsets.UTF_8).use { json.decodeFromString(TranslationFile.serializer(), it.readText()) }
            if (normalizeLanguage(file.language) != lang) return@runCatching emptyMap()
            file.disclosures.mapNotNull { it.toDisclosure(lang) }.associateBy { it.flow }
        }.getOrDefault(emptyMap())
    }
}

/** See [Disclosures.EQUIVALENT_EARLIER]. */
internal data class EquivalentText(val flow: DataFlow, val version: Int, val hash: String, val sameAsVersion: Int)

/** `disclosures-<lang>.json`: one entry per flow, each naming the source version it translates. */
@Serializable
internal data class TranslationFile(val language: String, val disclosures: List<TranslatedDisclosure>)

@Serializable
internal data class TranslatedDisclosure(
    val flow: String,
    val version: Int,
    val title: String,
    val whatIsSent: List<String>,
    val whatIsNotSent: List<String> = emptyList(),
    val sentTo: String,
    val why: String,
    val whenSent: String,
    val retention: String,
    val howToTurnOff: String,
) {
    /** Null for an unknown flow or an incomplete entry: the English text is shown instead. */
    fun toDisclosure(language: String): Disclosure? {
        val dataFlow = DataFlow.byId(flow) ?: return null
        val fields = listOf(title, sentTo, why, whenSent, retention, howToTurnOff) + whatIsSent + whatIsNotSent
        if (whatIsSent.isEmpty() || fields.any { it.isBlank() }) return null
        return Disclosure(dataFlow, version, title, whatIsSent, whatIsNotSent, sentTo, why, whenSent, retention, howToTurnOff, language)
    }
}
