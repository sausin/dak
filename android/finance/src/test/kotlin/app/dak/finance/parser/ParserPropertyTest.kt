package app.dak.finance.parser

import app.dak.finance.money.DigitNormalizer
import app.dak.finance.money.MoneyParser
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Property tests over seeded random SMS-like bodies built from real transaction-SMS vocabulary: the money and
 * transaction parsers never throw, stay fast, and never invent a number that is not in the text. Fixed seeds, so a
 * failure reproduces exactly (the failing body is in the message).
 */
class ParserPropertyTest {

    private val words = listOf(
        "Rs", "Rs.", "INR", "₹", "USD", "$", "debited", "credited", "spent", "received", "sent", "paid", "transferred",
        "refunded", "reversed", "from", "to", "your", "A/c", "a/c", "Card", "Credit Card", "Debit Card", "XX1234", "X5073",
        "**5678", "ending 4321", "beneficiary", "payee", "Avl Bal", "Bal", "Avl Lmt", "limit", "cashback", "fee", "GST",
        "via", "UPI", "NEFT", "IMPS", "Ref", "on", "at", "AMAZON", "will be", "not", "failed", "OTP", "is", "lakh",
        "crore", "Cr", "Dr", "/-", "(", ")", ".", ",", ":", ";", "\n", "-", "@", "#", "Info:", "folio", "units", "NAV",
        "SIP", "loan", "EMI", "wallet", "due on", "statement", "request", "the", "for", "by", "with", "of",
    )

    /** Locale-independent formatting, so the generated corpus is the same on every machine. */
    private fun fmt(pattern: String, vararg args: Any): String = String.format(java.util.Locale.ROOT, pattern, *args)

    private fun number(r: Random): String = when (r.nextInt(8)) {
        0 -> r.nextInt(1, 1000).toString()
        1 -> fmt("%d,%03d", r.nextInt(1, 100), r.nextInt(1000))
        2 -> fmt("%d,%02d,%03d", r.nextInt(1, 100), r.nextInt(100), r.nextInt(1000))
        3 -> fmt("%d.%02d", r.nextInt(1, 100_000), r.nextInt(100))
        4 -> fmt("%d,%03d.%02d", r.nextInt(1, 1000), r.nextInt(1000), r.nextInt(100))
        5 -> r.nextLong(1, 10_000_000_000L).toString()
        6 -> fmt("%d.%d", r.nextInt(1, 100), r.nextInt(10))
        else -> "0" + r.nextInt(100)
    }

    private fun body(r: Random): String {
        val sb = StringBuilder()
        repeat(r.nextInt(3, 30)) {
            if (r.nextInt(3) == 0) sb.append(number(r)) else sb.append(words[r.nextInt(words.size)])
            sb.append(if (r.nextInt(6) == 0) "" else " ")
        }
        var s = sb.toString()
        // Now and then write the digits in Devanagari, as some Indian banks do.
        if (r.nextInt(10) == 0) s = s.map { if (it in '0'..'9') '०' + (it - '0') else it }.joinToString("")
        return s
    }

    private fun bodies(seed: Int, n: Int): List<String> {
        val r = Random(seed)
        return List(n) { body(r) }
    }

    @Test
    fun `money occurrences are made of the digits in the text`() {
        for (text in bodies(seed = 20260923, n = 4_000)) {
            val normalized = DigitNormalizer.normalizeDigits(text)
            for (o in MoneyParser.findAll(text)) {
                assertEquals(normalized.substring(o.range), o.rawText, text)
                assertTrue(o.money.amountMinor >= 0, "negative amount ${o.money} in: $text")
                assertTrue(o.money.currency.length == 3 && o.money.currency == o.money.currency.uppercase(), text)
                if (Regex("(?i)lakh|lac|crore").containsMatchIn(o.rawText)) continue
                // No multiplier: the integer part is a prefix of the digits written (no invented or reordered digit).
                val written = o.rawText.filter { it.isDigit() }.trimStart('0')
                val whole = o.money.toBigDecimal().toBigInteger().toString()
                assertTrue(whole == "0" || written.startsWith(whole), "amount ${o.money} not from '${o.rawText}' in: $text")
            }
        }
    }

    @Test
    fun `transactions only carry amounts and account digits found in the text`() {
        var parsed = 0
        for ((i, text) in bodies(seed = 7, n = 4_000).withIndex()) {
            val sender = if (i % 2 == 0) "VM-HDFCBK-S" else "+919812345678"
            val txn = try {
                TransactionParser.parse(sender, text)
            } catch (e: Exception) {
                fail("parse threw ${e::class.simpleName} on: $text", e)
            } ?: continue
            parsed++
            val normalized = DigitNormalizer.normalizeDigits(text)
            val digits = normalized.filter { it.isDigit() }
            assertTrue(txn.amountMinor >= 0, "negative amount in: $text")
            assertTrue(txn.currency.matches(Regex("[A-Z]{3}")), "currency ${txn.currency} in: $text")
            val amounts = MoneyParser.findAll(text).map { it.money }
            val fromMoney = amounts.any { it.amountMinor == txn.amountMinor && it.currencyUpper == txn.currency }
            val whole = (txn.amountMinor / 100).toString()
            assertTrue(fromMoney || whole in digits, "amount ${txn.amountMinor} ${txn.currency} not in: $text")
            txn.last4?.let { l4 ->
                assertTrue(l4.all { it.isDigit() } && l4.length <= 4 && l4 in digits, "last4 $l4 not in: $text")
            }
            txn.maskedNumber?.let { m ->
                val visible = m.filter { it.isDigit() }
                assertTrue(visible in digits, "masked $m not in: $text")
                assertEquals(txn.last4, visible.takeLast(4), "last4 is not the tail of $m in: $text")
            }
            txn.balanceMinor?.let { b ->
                assertTrue(amounts.any { it.amountMinor == b }, "balance $b is not a stated amount in: $text")
            }
        }
        // The generator must actually exercise the transaction path, not only the gates.
        assertTrue(parsed > 200, "only $parsed of 4000 random bodies parsed as transactions")
    }

    @Test
    fun `bill reminders and search mentions never throw`() {
        for (text in bodies(seed = 99, n = 2_000)) {
            TransactionParser.parseBillReminder("VM-HDFCBK", text)?.let { assertTrue(it.amountMinor >= 0, text) }
            for (m in MoneyParser.findAllForSearch(text)) {
                assertTrue(m.major.signum() >= 0, text)
                assertTrue(m.range.first >= 0 && m.range.last < text.length, text)
            }
        }
    }
}
