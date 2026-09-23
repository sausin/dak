package app.dak.classify.text

import app.dak.classify.TemplateBundle
import app.dak.classify.bench.SyntheticCorpus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class RequiredLiteralsTest {

    @Test
    fun extractsAlternationsAndRuns() {
        assertEquals(setOf("otp", "time password", "verification code", "security code"),
            RequiredLiterals.of("""\b(otp|one[- ]?time password|verification code|security code)\b"""))
        assertEquals(setOf("do not share this otp", "do not share this code", "do not share your otp", "do not share your code"),
            RequiredLiterals.of("""\bdo not share (this|your) (otp|code)\b"""))
        assertEquals(setOf("code"), RequiredLiterals.of("""\bcode\s*(is|:)\s*\d{4,8}\b"""))
        assertEquals(setOf("ओटीपी", "सत्यापन कोड", "वन टाइम पासवर्ड"), RequiredLiterals.of("(ओटीपी|सत्यापन कोड|वन टाइम पासवर्ड)"))
        assertEquals(setOf("abc"), RequiredLiterals.of("(?i)ABC"))
        assertEquals(setOf("a/c"), RequiredLiterals.of("""x?a/c\s+\d+"""))
    }

    @Test
    fun optionalPartsAreNeverRequired() {
        assertEquals(setOf("debit"), RequiredLiterals.of("debit(ed)?"))
        assertNull(RequiredLiterals.of("(debit)?"))
        assertNull(RequiredLiterals.of("""a*\d+"""))
        assertNull(RequiredLiterals.of("abc|"))
        assertNull(RequiredLiterals.of("""(abc|\d+)"""))
    }

    @Test
    fun givesUpOnUnsupportedConstructs() {
        for (p in listOf("""\Qabc\E""", """\x41bc""", "\\u0041bc", """(?x)a b c""", """(?u)abc""", """(?<n>a)\k<n>""", "(abc", "abc)", "a{2", "[abc")) {
            assertNull(RequiredLiterals.of(p), p)
        }
    }

    /** Soundness: whenever a regex matches, the matched text contains one of the regex's required literals. */
    @Test
    fun everyMatchContainsARequiredLiteral() {
        val patterns = TemplateBundle.loadDefault().rules.map { it.pattern } + EXTRA_PATTERNS
        val texts = SyntheticCorpus.generate(8_000, seed = 11).map { it.body } + mutations()
        var checked = 0
        for (p in patterns) {
            val literals = RequiredLiterals.of(p) ?: continue
            val regex = Regex(p, RegexOption.IGNORE_CASE)
            for (t in texts) {
                for (m in regex.findAll(t)) {
                    val folded = m.value.map(AhoCorasick::fold).joinToString("")
                    if (literals.none { folded.contains(it) }) fail("'$p' matched '${m.value}' without any of $literals")
                    checked++
                }
            }
        }
        assertTrue(checked > 10_000, "only $checked matches checked")
    }

    private fun mutations(): List<String> {
        val r = Random(3)
        val words = listOf("OTP", "Otp", "KYC", "kyc", "debited", "Credited", "a/c", "XX1234", "ending", "Rs.", "₹", "upi", "ref",
            "पैसे", "ओटीपी", "click here", "https://x.io", "% off", "flat 20% off", "code: 1234", "Code is 99887", "avl. bal", "avlbl bal")
        return List(3_000) { List(1 + r.nextInt(6)) { words[r.nextInt(words.size)] }.joinToString(if (r.nextBoolean()) " " else "") }
    }

    private companion object {
        val EXTRA_PATTERNS = listOf(
            """(?:\brs\.?|\binr|₹|\brupees?|रु\.?|\$|€|\b(?:usd|eur|gbp|aed))\s?(\d[\d,]{0,14}(?:\.\d{1,2})?)""",
            """(?<![\d.])(\d[\d,]{0,14}(?:\.\d{1,2})?)\s?(?:/-|rs\b|rupees?\b|रुपये|रु\.?|rupaye\b|rupay\b)""",
            """a/c|\ba\.c\b|\bacc?t\b|\baccount|\bac\s?no|\bbank\b|\bupi\b|(?<![x*])[x*]{2,}+\d{2,6}|खाते""",
            """\b(?:click|tap|press)\b[^.!?\n]{0,40}\b(?:accept|receive|claim|get)\b""",
            """cashback up ?to|get flat|avail (?:the )?offer|%\s?off|use code|win\s|t&c appl""",
            """xx+\d{3}""",
        )
    }
}
