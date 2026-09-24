package app.dak.classify

import app.dak.classify.text.GatedRegex

/**
 * The "callback" scam: a message from a stranger's phone number that claims a problem with the reader's card, account,
 * order or a legal case and asks them to call a personal or ordinary number ("Your debit card has been locked. Call us
 * at 312-555-0123", "If this was not you, call our fraud team on 07700 900210", "تم إيقاف حسابك ... اتصل على ...").
 * The number then leads to a fake "fraud team" that asks for OTPs, card details or a screen-sharing app.
 *
 * Banks and services that do send such alerts give a toll-free line (1800 / 1860 in India, 800 / 888 / 877 / 866 / 855
 * / 844 / 833 in North America, 0800 / 0808 in the UK, 800 / 600 in the UAE) and send from registered names or short
 * codes, so a call to any other number, from a stranger's number, is the scam's shape. Evaluated per message by
 * [ClassifierPipeline] for unknown phone numbers only (not a template rule: telling toll-free lines apart needs digits,
 * and template rules must be digit-blind for the template cache).
 */
internal object CallbackCheck {

    /** English: a problem claim (or "if this was not you"), then "call" and a number that is not a toll-free line. */
    private val english = GatedRegex(
        """(?:\byour\s+(?:[\w-]+\s+){0,2}?(?:card|account|a/c|acct|payee|order|purchase|subscription|sim)\b[^\n]{0,80}?\b(?:locked|blocked|suspended|frozen|deactivated|compromised|on hold|restricted|placed|used)\b|\b(?:suspicious|unauthori[sz]ed|unusual|fraudulent)\s+(?:activity|transaction|login|access|payment|purchase|charge)\b|\bnew\s+payee\b|\bif\s+(?:this|it)\s+(?:was|is)(?:\s+not|n't)\s+(?:you|done\s+by\s+you|made\s+by\s+you|authori[sz]ed\s+by\s+you)\b|\bif\s+you\s+did(?:\s+not|n't)\s+(?:make|authori[sz]e|request|initiate|place)\b|\bnot\s+you\?|\b(?:lawsuit|arrest\w*|warrant|legal\s+action)\b)[^\n]{0,160}?\b(?:call|contact|ring|dial|phone)\b[^\d\n]{0,40}?(?<![\d+])(?!(?:\+?1[\s.-]?)?\(?8(?:00|88|77|66|55|44|33)\b|0800|0808|18[06]0)\+?\d[\d\s().-]{6,16}\d""",
        RegexOption.IGNORE_CASE,
    )

    /** Arabic: "account / card suspended ... call <number>", not the UAE 800 / 600 service lines. */
    private val arabic = GatedRegex(
        """(?:إيقاف|تعليق|حظر|تجميد|إغلاق)[^\n]{0,60}?(?:حساب|بطاق)[^\n]{0,160}?(?:الاتصال|اتصل)[^\d\n]{0,60}?(?<![\d+])(?!800|600)\+?\d[\d\s().-]{6,16}\d""",
    )

    /** Every pattern, for the prefilter equivalence test. */
    internal val allPatterns: List<GatedRegex> get() = listOf(english, arabic)

    /** True when [body] (the analysis text of a message) asks the reader to call back about a claimed problem. */
    fun matches(body: String): Boolean = english.containsMatchIn(body) || arabic.containsMatchIn(body)
}
