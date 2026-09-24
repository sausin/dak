package app.dak.classify.adversarial

import app.dak.classify.ClassifierPipeline
import app.dak.classify.ExtractedLink
import app.dak.classify.LinkExtractor
import app.dak.classify.LinkPresence
import app.dak.classify.LinkRisk
import app.dak.classify.LinkVerdict
import app.dak.classify.LookalikeDomainChecker
import app.dak.classify.Masker
import app.dak.classify.NaiveBayesModel
import app.dak.classify.OtpExtractor
import app.dak.classify.SenderId
import app.dak.classify.SenderKind
import app.dak.classify.SenderNameCheck
import app.dak.classify.SenderRegion
import app.dak.classify.TemplateBundle
import app.dak.classify.adversarial.AdversarialCorpus.Entry
import app.dak.classify.adversarial.AdversarialCorpus.Expect
import app.dak.classify.entities.EntityExtractor
import app.dak.classify.entities.EntitySpan
import app.dak.classify.scam.FakeCreditDetector
import app.dak.classify.scam.HintDirection
import app.dak.classify.scam.RecentMessage
import app.dak.classify.scam.ScamLabels
import app.dak.classify.scam.ScamLevel
import app.dak.classify.scam.ScamVerdict
import app.dak.classify.scam.TransactionHint
import app.dak.classify.unicode.UntrustedText
import app.dak.core.model.Category
import app.dak.core.model.Classification
import app.dak.core.model.ExtractedTransaction
import app.dak.core.model.OtpInfo
import app.dak.core.model.TransactionDirection
import app.dak.finance.money.CurrencyTable
import app.dak.finance.parser.TransactionParser
import app.dak.search.FtsMatch
import app.dak.search.QueryParser
import app.dak.search.TextNormalizer
import kotlinx.coroutines.runBlocking
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Currency
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs every message of the adversarial corpus (`shared/adversarial/`, see its README) through the app's defences
 * the way the indexer does (`DefaultMessageEnricher`: classifier pipeline with the SIM's region, transaction parser,
 * fake-credit detector), plus the link-safety check, OTP extraction, entity spans, the bidi sanitisers, the cloud
 * masker and the search-query builder, and checks:
 *
 * - **Robustness** for every line: nothing throws (not even an `Error`), the whole run of one message stays under a
 *   time budget (catches catastrophic regex backtracking), and a set of invariants holds (links never carry a
 *   non-http scheme or an invisible character, entity spans stay inside the body, isolated text cannot escape its
 *   isolate, FTS MATCH strings stay in the safe grammar, amounts stay positive and bounded...).
 * - **Expectations** of each line (`scam`, `not-scam`, `otp:123456`, ...).
 *
 * Every problem is collected and reported together, with `file:line [id]`. Lines tagged `known-gap` are known misses of
 * the current defences: their failures are printed, not fatal, and a known-gap line that now passes fails the test so
 * the tag gets removed.
 *
 * The per-message budget defaults to 500 ms (a slow message is retried once, so a GC pause is not a failure); set
 * `-Ddak.adversarial.budgetMs=…` to change it.
 */
class AdversarialCorpusTest {

    private val corpus = loaded

    @Test
    fun `corpus files parse strictly`() {
        if (corpus.errors.isNotEmpty()) {
            fail("${corpus.errors.size} problem(s) in ${AdversarialCorpus.DIR}:\n" + corpus.errors.joinToString("\n"))
        }
        assertTrue(corpus.entries.size >= 150, "corpus unexpectedly small: ${corpus.entries.size} entries")
    }

    @Test
    fun `corpus keeps benign look-alikes next to scams in every region file`() {
        val problems = ArrayList<String>()
        for ((file, entries) in corpus.entries.groupBy { it.file }) {
            if (file.startsWith("pwn/")) continue
            val benign = entries.count { e -> e.expects.any { it === Expect.NotScam } }
            val scams = entries.count { e -> e.expects.any { it === Expect.Scam || it === Expect.LikelyScam || it === Expect.Suspicious || it === Expect.FakeCredit || it === Expect.Spam } }
            if (benign == 0) problems += "$file has no 'not-scam' (benign look-alike) lines: false positives matter as much as misses"
            if (scams == 0) problems += "$file has no scam lines"
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `every message survives every defence within budget and meets its expectations`() {
        val byId = corpus.entries.associateBy { it.id }
        val observations = HashMap<String, Observation>()
        val failures = ArrayList<String>()
        val gaps = ArrayList<String>()
        val fixedGaps = ArrayList<String>()
        // Warm up: load the template bundle, model, confusable tables and JIT on a few ordinary messages first.
        corpus.entries.filter { it.body.length < 400 }.take(20).forEach { runCatching { harness.observe(it, emptyList()) } }

        val timings = ArrayList<Pair<Long, String>>()
        for (entry in corpus.entries) {
            val recent = entry.after?.let { id ->
                val prior = byId.getValue(id)
                val flagged = observations[id]?.let { ScamLabels.isFlaggedCredit(ScamLabels.toLabels(it.verdict)) } ?: false
                listOf(RecentMessage(prior.sender, prior.body, NOW - HOUR, flaggedCredit = flagged))
            }.orEmpty()
            var obs = harness.observe(entry, recent)
            if (obs.elapsedMs > budgetMs) obs = harness.observe(entry, recent).let { if (it.elapsedMs < obs.elapsedMs) it else obs }
            observations[entry.id] = obs
            timings += obs.elapsedMs to entry.where

            val problems = ArrayList<String>()
            obs.crash?.let { problems += "CRASH in ${it.first}: ${it.second}" }
            if (obs.elapsedMs > budgetMs) problems += "TOO SLOW: ${obs.elapsedMs} ms > $budgetMs ms (${obs.timings})"
            problems += obs.invariantViolations
            if (obs.crash == null) {
                for (expect in entry.expects) check(expect, obs)?.let { problems += "expected $expect but $it" }
            }
            when {
                problems.isEmpty() && entry.knownGap -> fixedGaps += "${entry.where}: passes now; remove the 'known-gap' tag"
                problems.isEmpty() -> Unit
                entry.knownGap -> gaps += "${entry.where}: ${problems.joinToString("; ")}"
                else -> failures += "${entry.where}: ${problems.joinToString("; ")}\n    ${obs.summary()}"
            }
        }

        println("Adversarial corpus: ${corpus.entries.size} messages in ${corpus.files.size} files, budget $budgetMs ms per message")
        println("Slowest: " + timings.sortedByDescending { it.first }.take(5).joinToString { "${it.second} ${it.first} ms" })
        corpus.entries.groupBy { it.file }.forEach { (file, list) -> println("  $file: ${list.size}") }
        if (gaps.isNotEmpty()) println("Known gaps (${gaps.size}), tolerated:\n  " + gaps.joinToString("\n  "))
        val all = failures + fixedGaps
        if (all.isNotEmpty()) fail("${all.size} adversarial corpus failure(s):\n" + all.joinToString("\n"))
    }

    // ------------------------------------------------------------------------------------------------ expectations

    /** Null when [expect] holds for [obs], else what was observed instead. */
    private fun check(expect: Expect, obs: Observation): String? {
        val level = obs.verdict.level
        fun verdictText() = "detector=$level ${obs.verdict.reasons.map { it.code }}, category=${obs.classification.category}, labels=${obs.labels}"
        return when (expect) {
            Expect.Scam -> if (obs.warned) null else "no warning (${verdictText()})"
            Expect.NotScam -> if (!obs.warned) null else "warned (${verdictText()})"
            Expect.LikelyScam -> if (level == ScamLevel.LIKELY_SCAM) null else verdictText()
            Expect.Suspicious -> if (level == ScamLevel.SUSPICIOUS) null else verdictText()
            Expect.FakeCredit -> if (level != ScamLevel.NONE) null else verdictText()
            Expect.NoFakeCredit -> if (level == ScamLevel.NONE) null else verdictText()
            Expect.Spam -> if (obs.classification.category == Category.SPAM) null else verdictText()
            Expect.NotSpam -> if (obs.classification.category != Category.SPAM) null else verdictText()
            is Expect.Category -> if (obs.classification.category == expect.category) null else verdictText()
            is Expect.Reason -> if (obs.verdict.reasons.any { it.code == expect.code }) null else verdictText()
            is Expect.Label -> if (expect.label in obs.labels) null else "labels=${obs.labels}"
            is Expect.NoLabel -> if (expect.label !in obs.labels) null else "labels=${obs.labels}"
            Expect.LinkWarning -> if (obs.linkWarning) null else "links=${obs.linkText()}, labels=${obs.labels}"
            Expect.NoLinkWarning -> if (!obs.linkWarning) null else "links=${obs.linkText()}, labels=${obs.labels}"
            Expect.Lookalike -> if (obs.linkVerdicts.any { it.risk == LinkRisk.LOOKALIKE }) null else "links=${obs.linkText()}"
            is Expect.Links -> if (obs.links.size == expect.count) null else "links=${obs.linkText()}"
            is Expect.LinkHost -> if (obs.links.any { it.asciiHost == expect.host || it.host == expect.host }) null else "links=${obs.linkText()}"
            is Expect.Otp -> if (obs.otp?.code == expect.code) null else "otp=${obs.otp?.code}"
            Expect.NoOtp -> if (obs.otp == null) null else "otp=${obs.otp.code}"
            is Expect.Txn -> {
                val want = if (expect.credit) TransactionDirection.CREDIT else TransactionDirection.DEBIT
                if (obs.parsed?.direction == want) null else "txn=${obs.parsed?.direction} ${obs.parsed?.amountMinor}"
            }
            is Expect.Amount -> if (obs.parsed?.amountMinor == expect.minor) null else "txn=${obs.parsed?.direction} amountMinor=${obs.parsed?.amountMinor}"
            Expect.NoTxn -> if (obs.parsed == null) null else "txn=${obs.parsed.direction} amountMinor=${obs.parsed.amountMinor} ${obs.parsed.currency}"
            Expect.SenderSpoof -> if (obs.senderSpoof) null else "sender not flagged by SenderNameCheck"
            Expect.NoCrash -> null // crashes, time and invariants are checked for every line
        }
    }

    // ------------------------------------------------------------------------------------------------ harness

    class Observation(
        val classification: Classification,
        val verdict: ScamVerdict,
        val labels: Set<String>,
        val links: List<ExtractedLink>,
        val linkVerdicts: List<LinkVerdict>,
        val otp: OtpInfo?,
        val parsed: ExtractedTransaction?,
        val senderSpoof: Boolean,
        val crash: Pair<String, String>?,
        val invariantViolations: List<String>,
        val elapsedMs: Long,
        val timings: String,
    ) {
        /** Any warning the user would see on the message itself. */
        val warned: Boolean
            get() = verdict.level != ScamLevel.NONE || classification.category == Category.SPAM || "fraud-risk" in labels

        /** A risky link (per the link-safety check) or the unknown-sender-link label. */
        val linkWarning: Boolean
            get() = "unknown-sender-link" in labels || linkVerdicts.any { it.risk != LinkRisk.OFFICIAL && it.risk != LinkRisk.UNKNOWN }

        fun linkText(): String = linkVerdicts.joinToString(prefix = "[", postfix = "]") { "${it.link.raw.take(60)}=${it.risk}" }

        fun summary(): String =
            "observed: category=${classification.category}/${classification.source} labels=$labels detector=${verdict.level}" +
                "${verdict.reasons.map { it.code }} otp=${otp?.code} txn=${parsed?.direction}:${parsed?.amountMinor} links=${linkText()}"
    }

    private class Harness {
        val templates = TemplateBundle.loadDefault()
        val model = NaiveBayesModel.loadDefault()
        val detector = FakeCreditDetector(templates)
        val checker = LookalikeDomainChecker()
        private val contacts: Set<String> = loaded.entries.filter { it.isContact }.mapTo(HashSet()) { it.sender }
        private val pipelines = HashMap<String?, ClassifierPipeline>()

        private fun pipeline(region: String?): ClassifierPipeline = pipelines.getOrPut(region) {
            ClassifierPipeline(templates, model, contactLookup = { it in contacts }, regionFor = { SenderRegion.of(region) })
        }

        fun observe(entry: Entry, recent: List<RecentMessage>): Observation {
            val body = entry.body
            val sender = entry.sender
            val timings = StringBuilder()
            val violations = ArrayList<String>()
            var crash: Pair<String, String>? = null
            val start = System.nanoTime()

            fun <T> step(name: String, fallback: T, block: () -> T): T {
                if (crash != null) return fallback
                val t0 = System.nanoTime()
                return try {
                    block()
                } catch (e: Throwable) { // StackOverflowError included: an Error in the indexer is a crash too
                    crash = name to "${e.javaClass.name}: ${e.message?.take(200)}"
                    fallback
                } finally {
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    if (ms >= 5) timings.append("$name=${ms}ms ")
                }
            }

            val isContact = entry.isContact
            // --- What DefaultMessageEnricher does for an incoming message.
            val classification = step("ClassifierPipeline", Classification(Category.UNKNOWN, 0f, app.dak.core.model.ClassifierSource.NONE)) {
                runBlocking { pipeline(entry.region).classify(sender, body, 1) }
            }
            val symbols = CurrencyTable.symbolMapFor(currencyOf(entry.region))
            val gatedTxn = step("TransactionParser", null) {
                if (shouldParseTransaction(sender, classification.category, isContact)) TransactionParser.parse(sender, body, symbols) else null
            }
            val candidate = step("FakeCreditDetector.isCandidate", false) { detector.isCandidate(sender, body, entry.region) }
            step("FakeCreditDetector.needsRecentMessages", false) { detector.needsRecentMessages(sender, body, entry.region) }
            val hint = gatedTxn?.let {
                TransactionHint(
                    direction = if (it.direction == TransactionDirection.CREDIT) HintDirection.CREDIT else HintDirection.DEBIT,
                    amountMinor = it.amountMinor,
                    last4 = it.last4,
                )
            }
            // The enricher only evaluates candidates; the detector is evaluated anyway here, to check that claim.
            val fullVerdict = step("FakeCreditDetector.evaluate", ScamVerdict.None) {
                detector.evaluate(sender, body, hint, emptySet(), isContact, recent, NOW, entry.region)
            }
            if (!candidate && fullVerdict.level != ScamLevel.NONE) {
                violations += "isCandidate=false but evaluate() flags it ${fullVerdict.level} ${fullVerdict.reasons}"
            }
            val verdict = if (candidate) fullVerdict else ScamVerdict.None
            val scamLabels = ScamLabels.toLabels(verdict)
            if (verdict.level != ScamLevel.NONE && ScamLabels.fromLabels(scamLabels)?.level != verdict.level) {
                violations += "scam labels do not round-trip: $scamLabels"
            }
            val labels = classification.labels + scamLabels

            // --- The parser on its own (what `txn:` / `amount:` / `no-txn` check), whatever the category.
            val parsed = step("TransactionParser(direct)", null) { TransactionParser.parse(sender, body, symbols) }
            for (t in listOfNotNull(parsed, gatedTxn)) {
                if (t.amountMinor <= 0 || t.amountMinor > MAX_SANE_MINOR) violations += "absurd transaction amount ${t.amountMinor} ${t.currency}"
                if (t.balanceMinor != null && t.balanceMinor!! > MAX_SANE_MINOR) violations += "absurd balance ${t.balanceMinor}"
                if (t.currency.length != 3) violations += "odd currency '${t.currency}'"
            }

            // --- Links: extraction and the safety check behind the "open link?" dialog.
            val links = step("LinkExtractor", emptyList()) { LinkExtractor.extract(body) }
            val linkVerdicts = step("LookalikeDomainChecker", emptyList()) { links.map { checker.check(it) } }
            val present = step("LinkPresence", false) { LinkPresence.containsLink(body) }
            if (links.isNotEmpty() && !present) violations += "LinkExtractor found links but LinkPresence (has:link) says none"
            for (v in linkVerdicts) violations += linkInvariants(v)

            // --- OTP (copy chip / autofill) and entity spans (tappable text in the bubble).
            val otp = step("OtpExtractor", null) { OtpExtractor.extract(body.take(ClassifierPipeline.MAX_CLASSIFY_CHARS)) }
            otp?.code?.let { code ->
                if (!code.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }) violations += "OTP code '$code' is not ASCII alphanumeric"
            }
            classification.otp?.code?.let { code ->
                if (classification.category != Category.OTP) violations += "OTP chip $code on a ${classification.category} message"
            }
            val spans = step("EntityExtractor", emptyList()) { EntityExtractor.extract(body, entry.region) }
            violations += spanInvariants(spans, body)

            // --- Untrusted-text sanitisers used when the body / sender is shown inside Dak's own strings.
            step("UntrustedText", Unit) {
                val kept = UntrustedText.neutralizeKeepingOffsets(body)
                if (kept.length != body.length) violations += "neutralizeKeepingOffsets changed the length"
                if (kept.any(UntrustedText::isScopedBidiControl)) violations += "neutralizeKeepingOffsets left a bidi control"
                val isolated = UntrustedText.isolate(body)
                if (isolated.substring(1, isolated.length - 1).any(UntrustedText::isScopedBidiControl)) violations += "isolate() leaves a bidi control inside"
                val name = UntrustedText.sanitizeName(sender)
                if (name.any { UntrustedText.isBidiControl(it) || it in INVISIBLE }) violations += "sanitizeName left a bidi/invisible character in the sender"
            }
            val senderSpoof = step("SenderNameCheck", false) { SenderNameCheck.isSuspicious(sender) }

            // --- Cloud masker (opt-in cloud stage): must never throw and never leak a digit.
            step("Masker", Unit) {
                val masked = Masker.mask(body.take(ClassifierPipeline.MAX_CLASSIFY_CHARS))
                if (masked.any { it.isDigit() }) violations += "Masker leaked a digit"
            }

            // --- Search: the body pasted into the search box must give a safe FTS MATCH string.
            step("Search", Unit) {
                TextNormalizer.normalize(body.take(SEARCH_CHARS))
                val query = QueryParser.parse(body.take(SEARCH_CHARS), SEARCH_NOW, Locale.ROOT)
                FtsMatch.build(query.textExpr, prefixLastTerm = true)?.let { violations += ftsInvariants(it) }
            }

            val elapsed = (System.nanoTime() - start) / 1_000_000
            return Observation(
                classification, verdict, labels, links, linkVerdicts, otp, parsed, senderSpoof, crash, violations, elapsed,
                timings.toString().trim(),
            )
        }
    }

    // ------------------------------------------------------------------------------------------------ invariants

    private companion object {
        val loaded: AdversarialCorpus.Result by lazy { AdversarialCorpus.load() }
        private val harness by lazy { Harness() }

        val budgetMs: Long = System.getProperty("dak.adversarial.budgetMs")?.toLongOrNull() ?: 500L

        const val NOW: Long = 1_758_600_000_000L
        const val HOUR: Long = 3_600_000L

        /** 10^13 major units (ten trillion rupees): anything above is an overflow or a misparse, not money. */
        const val MAX_SANE_MINOR: Long = 1_000_000_000_000_000L

        /** A search query is what a person types or pastes; longer input is cut here like the search box would. */
        const val SEARCH_CHARS: Int = 10_000
        val SEARCH_NOW: ZonedDateTime = ZonedDateTime.of(2026, 9, 24, 12, 0, 0, 0, ZoneOffset.UTC)

        val INVISIBLE: Set<Char> = setOf('\u200B', '\u2060', '\u2061', '\u2062', '\u2063', '\u2064', '\uFEFF', '\u180E')
        val BAD_SCHEMES = listOf("javascript:", "intent:", "content:", "file:", "tel:", "sms:", "smsto:", "data:", "market:", "mailto:", "vbscript:")

        fun currencyOf(region: String?): String? = region?.let {
            runCatching { Currency.getInstance(Locale("", it))?.currencyCode }.getOrNull()?.takeIf { c -> c != "XXX" }
        }

        /** `DefaultMessageEnricher.shouldParseTransaction` (in :core-index, an Android module), reproduced. */
        fun shouldParseTransaction(address: String, category: Category, isSavedContact: Boolean): Boolean = when (category) {
            Category.TRANSACTION -> !(isSavedContact && isPersonNumber(address))
            Category.UNKNOWN -> !isPersonNumber(address) &&
                SenderId.classify(address).let { it == SenderKind.DLT_HEADER || it == SenderKind.ALPHANUMERIC }
            else -> false
        }

        private fun isPersonNumber(address: String): Boolean {
            val trimmed = address.trim()
            val phoneShaped = trimmed.isNotEmpty() && trimmed.all { it.isDigit() || it in "+ -().\u00A0" }
            val compact = if (phoneShaped) trimmed.filter { it.isDigit() || it == '+' } else trimmed
            return SenderId.classify(compact) == SenderKind.PHONE_NUMBER
        }

        fun linkInvariants(v: LinkVerdict): List<String> {
            val link = v.link
            val out = ArrayList<String>()
            val raw = link.raw
            if (raw.isEmpty()) out += "empty link extracted"
            if (raw.any { it.isWhitespace() || UntrustedText.isBidiControl(it) || it in INVISIBLE || it == '\u200C' || it == '\u200D' || it == '\u00AD' }) {
                out += "link '${printable(raw)}' contains whitespace or an invisible/bidi character"
            }
            if (BAD_SCHEMES.any { raw.startsWith(it, ignoreCase = true) }) out += "link with a dangerous scheme: ${printable(raw)}"
            val url = link.url
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) out += "link opens a non-http URL: ${printable(url)}"
            link.asciiHost?.let { h -> if (h.any { it.code >= 0x80 || it.isWhitespace() }) out += "asciiHost '${printable(h)}' is not ASCII" }
            if (link.hasUserInfo && v.risk == LinkRisk.UNKNOWN) out += "userinfo link '${printable(raw)}' not flagged"
            if (link.isIdn && v.risk == LinkRisk.UNKNOWN) out += "IDN link '${printable(raw)}' not flagged"
            if (link.host != null && link.asciiHost == null && v.risk == LinkRisk.UNKNOWN) {
                out += "link '${printable(raw)}' a browser would refuse (no asciiHost) is not flagged"
            }
            return out
        }

        fun spanInvariants(spans: List<EntitySpan>, body: String): List<String> {
            val out = ArrayList<String>()
            var end = 0
            for (s in spans) {
                if (s.start < 0 || s.end > body.length || s.start >= s.end) {
                    out += "entity ${s.type} span ${s.start}..${s.end} outside the body (${body.length})"
                    continue
                }
                if (s.start < end) out += "entity ${s.type} at ${s.start} overlaps the previous span"
                end = maxOf(end, s.end)
                if (Character.isLowSurrogate(body[s.start]) || Character.isHighSurrogate(body[s.end - 1])) {
                    out += "entity ${s.type} span ${s.start}..${s.end} splits a surrogate pair"
                }
            }
            return out
        }

        fun ftsInvariants(match: String): List<String> {
            val out = ArrayList<String>()
            if (match.count { it == '"' } % 2 != 0) out += "FTS MATCH has unbalanced quotes: ${printable(match)}"
            var inQuotes = false
            for ((i, c) in match.withIndex()) {
                if (c == '"') inQuotes = !inQuotes
                if (inQuotes) continue
                if (c in "():^") out += "FTS MATCH has '$c' outside a phrase: ${printable(match)}"
                if (c == '*' && i + 1 < match.length && !match[i + 1].isWhitespace()) out += "FTS MATCH has '*' inside a term: ${printable(match)}"
            }
            return out.distinct()
        }

        /** [s] with invisible and control characters spelled out, for failure messages. */
        fun printable(s: String): String = buildString {
            for (c in s.take(120)) {
                if (c.code < 0x20 || c in '\u007F'..'\u009F' || UntrustedText.isBidiControl(c) || c in INVISIBLE || Character.isSurrogate(c)) {
                    append("\\u%04X".format(c.code))
                } else {
                    append(c)
                }
            }
            if (s.length > 120) append("…")
        }
    }
}
