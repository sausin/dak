package app.dak.automations.broadcast

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BroadcastSupportTest {

    @Test
    fun phoneKeyComparesLoosely() {
        assertTrue(PhoneKey.same("+91 98765-43210", "09876543210"))
        assertTrue(PhoneKey.same("0091 9876543210", "9876543210"))
        assertTrue(PhoneKey.same("+65 9123 4567", "91234567"))
        assertFalse(PhoneKey.same("9876543210", "9876543211"))
        assertFalse(PhoneKey.same("12345", "912345"))
        assertEquals("9876543210", PhoneKey.of("+91 98765 43210"))
        assertTrue(PhoneKey.isShortCode("56161"))
        assertFalse(PhoneKey.isShortCode("+91 98765 43210"))
        assertTrue(PhoneKey.isAlphanumeric("AX-AIRTEL"))
    }

    @Test
    fun pacingRespectsHistory() {
        val start = 1_000_000L
        val history = List(30) { start - 60_000L + it } // 30 sends a minute ago: window full
        val times = BroadcastPacing().schedule(5, start, history)
        // Next slot opens when the oldest of those leaves the 30-minute window.
        assertEquals(start - 60_000L + 30 * 60_000L, times.first())
        assertEquals(times.sorted(), times)
    }

    @Test
    fun quotaCountsLast24HoursWithoutCancelled() {
        val now = 10 * BroadcastLimits.DAY_MILLIS
        fun rec(created: Long, n: Int, cancelled: Int = 0) = BroadcastRecord(
            id = "r$created", listId = "l", listName = "L", template = "t", subId = 1, createdAt = created,
            recipients = List(n) { i ->
                BroadcastRecipient(
                    address = "98$i", text = "t", sendAtMillis = created,
                    status = if (i < cancelled) RecipientStatus.CANCELLED else RecipientStatus.SENT,
                )
            },
        )
        val records = listOf(rec(now - 1000, 30, cancelled = 5), rec(now - 2 * BroadcastLimits.DAY_MILLIS, 50), rec(now - 3_600_000, 10))
        assertEquals(35, BroadcastQuota.usedInLastDay(records, now))
        assertEquals(65, BroadcastQuota.remaining(records, now))
    }

    @Test
    fun tagRoundTrips() {
        val tag = BroadcastTag("b-1:x", 7)
        assertEquals(tag, BroadcastTag.decode(tag.encode()))
        assertNull(BroadcastTag.decode("birthday:ask:1:BIRTHDAY:2025"))
        assertNull(BroadcastTag.decode("broadcast:abc"))
        assertNull(BroadcastTag.decode(null))
        assertTrue(BroadcastTag.isBroadcast("broadcast:a:0"))
    }

    @Test
    fun termsVersioning() {
        assertTrue(BroadcastTerms.needsAcceptance(null))
        assertTrue(BroadcastTerms.needsAcceptance(BroadcastTerms.VERSION - 1))
        assertFalse(BroadcastTerms.needsAcceptance(BroadcastTerms.VERSION))
    }

    @Test
    fun codecRoundTripsAndToleratesGarbage() {
        val list = BroadcastList("id", "Family", listOf(Member(4L, "Maa", "+919800000000"), Member(address = "9811111111")), 5L)
        assertEquals(listOf(list), BroadcastCodec.decodeLists(BroadcastCodec.encodeLists(listOf(list))))
        assertEquals(emptyList(), BroadcastCodec.decodeLists("{not json"))
        assertEquals(emptyList(), BroadcastCodec.decodeRecords(null))
        val record = BroadcastRecord(
            "r", "id", "Family", "Hi {firstName}", 2, 9L, null,
            listOf(BroadcastRecipient("+919800000000", "Maa", 4L, "Hi Maa", 9L, 12L, "sms:5", RecipientStatus.SENT)),
        )
        assertEquals(listOf(record), BroadcastCodec.decodeRecords(BroadcastCodec.encodeRecords(listOf(record))))
    }

    @Test
    fun addMembersDedupesAndCaps() {
        val start = listOf(Member(address = "9811111111"))
        val (out, dropped) = BroadcastLists.addMembers(
            start,
            listOf(Member(address = "+91 98111 11111"), Member(address = "9822222222"), Member(address = "9833333333")),
            max = 2,
        )
        assertEquals(listOf("9811111111", "9822222222"), out.map { it.address })
        assertEquals(1, dropped)
        assertEquals(listOf("98111", "+91 97000 00000"), BroadcastLists.parseNumbers("98111, +91 97000 00000;ab\n12").map { it.address })
    }
}
