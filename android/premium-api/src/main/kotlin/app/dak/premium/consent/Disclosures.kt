package app.dak.premium.consent

import java.security.MessageDigest

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
 * The text of one prominent disclosure. [version] must be bumped whenever the meaning of any field changes: a consent
 * given to an older version no longer counts (see [ConsentLedger.isGranted]), so the user sees the new text first.
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
) {
    /** The exact text shown, in a stable order; its hash goes into every consent record. */
    fun canonicalText(): String = buildString {
        append(flow.id).append('\n').append(version).append('\n').append(title).append('\n')
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
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * The current disclosure for each [DataFlow]. Plain language, no legalese; the same facts as `docs/privacy-policy.md`.
 * Server-side promises here (retention, no training, no sale) are requirements on the premium backend, which does not
 * exist yet: `docs/privacy-compliance.md` tracks them.
 */
object Disclosures {
    private const val TURN_OFF = "Settings → Privacy → Data that leaves your phone. Turning it off stops it at once."

    val cloudClassification = Disclosure(
        flow = DataFlow.CLOUD_CLASSIFICATION,
        version = 2,
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
        version = 1,
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
        version = 1,
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
        version = 1,
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

    val all: List<Disclosure> = listOf(cloudClassification, webhooks, webRelay, aiSearch)

    fun forFlow(flow: DataFlow): Disclosure = all.first { it.flow == flow }
}
