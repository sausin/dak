package app.dak.automations.safety

import app.dak.automations.MessageEvent
import app.dak.automations.RuleEngine
import app.dak.automations.action.DefaultActionRegistry
import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Condition
import app.dak.automations.rule.Rule
import app.dak.automations.rule.Trigger
import app.dak.core.model.Category
import app.dak.premium.FreeEntitlements
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ReDoS harness for user-authored automation regexes (patterns are the user's, message bodies the attacker's). */
class RegexSafetyTest {

    private val exponential = listOf(
        "(a+)+$", "(a*)*b", "(a|aa)+$", "(a|a?)+", "(\\w+\\s?)*$", "^(\\d+)*$", "(.*a){2,}x", "((ab)*)+c",
        "(?:[a-z]+\\.)+com!", "(x+x+)+y", "([a-zA-Z0-9]+)*@", "(a?){25}a{25}", "(\\1)", "(a)\\1+", "(?<n>a)\\k<n>",
    )

    private val acceptable = listOf(
        "OTP", "(?i)otp\\s*is\\s*(\\d{4,8})", "\\b\\d{6}\\b", "^VM-HDFC", "(\\d{3}-)+\\d{4}", "a+b+c+",
        "[a-z]+@[a-z]+\\.com", "(?:foo)?bar", "(a+)++b", "(?>a+)+b", "(?i:bank|card) debited", "[(+*)]+x",
        "\\Q(a+)+\\E", "rs\\.?\\s*[\\d,]+(?:\\.\\d{2})?",
    )

    @Test
    fun `exponential patterns are rejected`() {
        for (p in exponential) assertNotNull(RegexSafety.problem(p), "accepted $p")
        assertNotNull(RegexSafety.problem("a".repeat(RegexSafety.MAX_PATTERN_LENGTH + 1)))
    }

    @Test
    fun `ordinary automation patterns are accepted`() {
        for (p in acceptable) assertNull(RegexSafety.problem(p), "rejected $p")
    }

    @Test
    fun `accepted patterns finish quickly on pathological bodies`() {
        val bodies = listOf(
            "a".repeat(50_000), "a".repeat(50_000) + "!", "1".repeat(50_000), " ".repeat(50_000) + "x",
            "ab".repeat(25_000), "a@".repeat(25_000), "1-".repeat(25_000), "rs " + "9,".repeat(25_000),
        ).map { it.take(RuleEngine.MAX_REGEX_INPUT_LENGTH) } // the engine's own cap
        for (p in acceptable) {
            val pattern = Pattern.compile(p)
            for (body in bodies) {
                val start = System.nanoTime()
                pattern.matcher(body).find()
                val ms = (System.nanoTime() - start) / 1_000_000
                assertTrue(ms < 500, "'$p' took ${ms}ms")
            }
        }
    }

    @Test
    fun `validator reports and engine skips an unsafe regex`() {
        val rule = Rule(
            id = "r", name = "r", trigger = Trigger.MessageReceived(), conditions = Condition.BodyMatches("(a+)+$"),
            actions = listOf(ActionSpec.Archive), createdAt = 0, updatedAt = 0,
        )
        val issues = RuleValidator.validate(rule, FreeEntitlements)
        assertTrue(issues.any { it is ValidationIssue.InvalidRegex && it.error.contains("exponential") })

        val hostileBody = "a".repeat(RuleEngine.MAX_REGEX_INPUT_LENGTH) + "!"
        val event = MessageEvent(
            messageKey = "sms:1", address = "+15551234567", mergeKey = null, body = hostileBody, dateMillis = 0,
            subId = 1, slot = 0, category = Category.PERSONAL, otp = null, transaction = null,
        )
        val start = System.nanoTime()
        assertTrue(RuleEngine.evaluate(event, listOf(rule)).isEmpty())
        assertTrue(System.nanoTime() - start < 1_000_000_000L)
    }

    @Test
    fun `whatsapp relay uri cannot be rewritten by the message text`() {
        val uri = DefaultActionRegistry().whatsAppUri("+919876543210", "hi&phone=+10000000000#frag ✓")
        assertFalse(uri.substringAfter("&text=").contains('&'))
        assertFalse(uri.contains('#'))
        assertEquals("whatsapp://send?phone=%2B919876543210&text=hi%26phone%3D%2B10000000000%23frag%20%E2%9C%93", uri)
    }
}
