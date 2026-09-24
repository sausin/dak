package app.dak.finance.ledger

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountMatcherTest {

    private val day = TimeUnit.DAYS.toMillis(1)

    private fun obs(
        id: String,
        digits: String?,
        first: Long = 0,
        last: Long = first,
        institution: String = "HDFC Bank",
        type: AccountType = AccountType.BANK_ACCOUNT,
    ) = AccountObservation(id, institution, type, digits, first, last)

    @Test
    fun `suffix of a longer mask is suggested`() {
        val old = obs("HDFC_BANK:BANK_ACCOUNT:440065", "440065", first = 0, last = 100 * day)
        val new = obs("HDFC_BANK:BANK_ACCOUNT:40065", "40065", first = 101 * day, last = 150 * day)
        val s = AccountMatcher.suggest(listOf(old, new)).single()
        assertEquals(new.accountId, s.accountA)
        assertEquals(old.accountId, s.accountB)
        assertEquals(AliasReason.SUFFIX_MATCH, s.reason)
        // 5 digits + non-overlapping timelines + recent
        assertEquals(50 + 20 + 10, s.score)
    }

    @Test
    fun `last 4 against a longer mask`() {
        val s = AccountMatcher.compare(obs("a", "0065"), obs("b", "440065"))
        assertEquals(AliasReason.LAST4_MATCH, s?.reason)
    }

    @Test
    fun `same digits under two instruments of one kind`() {
        val s = AccountMatcher.compare(
            obs("HDFC_BANK:UPI:1234", "1234"),
            obs("HDFC_BANK:BANK_ACCOUNT:1234", "1234"),
        )
        assertEquals(AliasReason.SAME_DIGITS, s?.reason)
    }

    @Test
    fun `numbers that differ in their visible part are never suggested`() {
        assertNull(AccountMatcher.compare(obs("a", "440065"), obs("b", "120065")))
        assertNull(AccountMatcher.compare(obs("a", "40065"), obs("b", "120065")))
    }

    @Test
    fun `fewer than four common digits are not enough`() {
        assertNull(AccountMatcher.compare(obs("a", "065"), obs("b", "440065")))
    }

    @Test
    fun `different institution or kind is never suggested`() {
        assertNull(AccountMatcher.compare(obs("a", "40065"), obs("b", "440065", institution = "ICICI Bank")))
        assertNull(AccountMatcher.compare(obs("a", "40065"), obs("b", "440065", type = AccountType.CREDIT_CARD)))
        assertNull(AccountMatcher.compare(obs("a", null), obs("b", "440065")))
    }

    @Test
    fun `decided and co-occurring pairs are skipped`() {
        val a = obs("a", "40065")
        val b = obs("b", "440065")
        val key = AccountMatcher.pairKey("b", "a")
        assertTrue(AccountMatcher.suggest(listOf(a, b), decidedPairs = setOf(key)).isEmpty())
        assertTrue(AccountMatcher.suggest(listOf(a, b), coOccurringPairs = setOf(key)).isEmpty())
    }

    @Test
    fun `accounts already merged are not suggested again`() {
        val a = obs("a", "40065")
        val b = obs("b", "440065")
        val aliases = AccountAliases(mapOf("a" to "b"))
        assertTrue(AccountMatcher.suggest(listOf(a, b), aliases = aliases).isEmpty())
    }

    @Test
    fun `overlapping timelines score lower than a format switch`() {
        val overlapping = AccountMatcher.compare(obs("a", "40065", 0, 100 * day), obs("b", "440065", 50 * day, 150 * day))!!
        val switched = AccountMatcher.compare(obs("a", "40065", 101 * day, 150 * day), obs("b", "440065", 0, 100 * day))!!
        assertTrue(switched.score > overlapping.score)
    }

    @Test
    fun `stale pairs lose the recency bonus`() {
        val stale = AccountMatcher.compare(obs("a", "40065", 1000 * day, 1001 * day), obs("b", "440065", 0, 1 * day))!!
        assertEquals(50 + 20, stale.score)
    }

    @Test
    fun `co-occurrence in one transfer message marks the pair distinct`() {
        val a = obs("a", "1234")
        val b = obs("b", "561234")
        val c = obs("c", "9999")
        val pairs = AccountMatcher.coOccurringPairs(
            listOf(a, b, c),
            sequenceOf("Rs 500 transferred from A/c XX561234 to A/c XX1234", "A/c XX9999 debited"),
        )
        assertEquals(setOf(AccountMatcher.pairKey("a", "b")), pairs)
    }

    @Test
    fun `masked number finder`() {
        assertEquals(listOf("440065", "1234"), MaskedNumbers.findAll("A/c XX440065 to card **1234, ref XX440065"))
        assertEquals(listOf("7788"), MaskedNumbers.findAll("Card ending in 7788"))
        assertTrue(MaskedNumbers.findAll("Rs 500 at 12:30").isEmpty())
        assertEquals(listOf("5073"), MaskedNumbers.findAll("Credited INR 50,000.00 to A/c X5073 on 06-AUG-2026"))
        assertTrue(MaskedNumbers.findAll("order X12345 shipped").isEmpty())
    }

    @Test
    fun `masked number finder reads the same formats as the parser`() {
        // Leading digits a bank shows before the mask do not belong to the visible tail.
        assertEquals(listOf("1234"), MaskedNumbers.findAll("Credit Card 4375XXXXXXXX1234 used for INR 780"))
        assertEquals(listOf("1234", "5678"), MaskedNumbers.findAll("from A/C: XX1234 to Ac No. ...5678"))
        assertEquals(listOf("1212"), MaskedNumbers.findAll("Rs 600 debited from A/c 1212 on 14-09-26"))
        // Dates and times after a keyword are not account numbers.
        assertTrue(MaskedNumbers.findAll("card 2026-09-14 and account 10:30").isEmpty())
    }
}
