package app.dak.automations

import app.dak.automations.birthdays.OccasionKind
import app.dak.automations.birthdays.WishTag
import app.dak.automations.broadcast.BroadcastTag
import app.dak.automations.broadcast.BroadcastTerms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [WishTag] and [BroadcastTag] strings are stored in the scheduled-sends table's `ruleId` column and read back by the
 * executor after upgrades, so their exact spelling is a persisted format. Golden strings below; never edit one
 * without keeping the old spelling decodable.
 */
class PersistedTagsTest {

    @Test
    fun `wish tag golden strings`() {
        assertEquals("birthday:ask:42:BIRTHDAY:2026", WishTag(ask = true, contactId = 42, kind = OccasionKind.BIRTHDAY, year = 2026).encode())
        assertEquals("birthday:auto:7:ANNIVERSARY:2027", WishTag(ask = false, contactId = 7, kind = OccasionKind.ANNIVERSARY, year = 2027).encode())
        assertEquals("birthday:sent:7:BIRTHDAY:2027", WishTag(ask = false, contactId = 7, kind = OccasionKind.BIRTHDAY, year = 2027, confirmed = true).encode())
        assertEquals(listOf("BIRTHDAY", "ANNIVERSARY"), OccasionKind.entries.map { it.name })
        assertEquals("7:ANNIVERSARY:2027", WishTag(false, 7, OccasionKind.ANNIVERSARY, 2027).dedupeKey)
    }

    @Test
    fun `wish tags decode from their golden strings`() {
        assertEquals(WishTag(true, 42, OccasionKind.BIRTHDAY, 2026, confirmed = false), WishTag.decode("birthday:ask:42:BIRTHDAY:2026"))
        assertEquals(WishTag(false, 7, OccasionKind.BIRTHDAY, 2027, confirmed = true), WishTag.decode("birthday:sent:7:BIRTHDAY:2027"))
        assertEquals(WishTag(false, -3, OccasionKind.ANNIVERSARY, 1999), WishTag.decode("birthday:auto:-3:ANNIVERSARY:1999"))
    }

    @Test
    fun `wish tag decode rejects near misses`() {
        listOf(
            "", "birthday:", "birthday:ask:42:BIRTHDAY", "birthday:ask:42:BIRTHDAY:2026:extra", "Birthday:ask:42:BIRTHDAY:2026",
            "birthday:ASK:42:BIRTHDAY:2026", "birthday:ask:42:birthday:2026", "birthday:ask::BIRTHDAY:2026",
            "birthday:ask:42:BIRTHDAY:", "birthday:ask:42:BIRTHDAY:20x6", "broadcast:b:1", "birthday:ask:4.2:BIRTHDAY:2026",
        ).forEach { assertNull(WishTag.decode(it), it) }
    }

    @Test
    fun `every wish tag round trips and the unattended flag follows the mode`() {
        for (ask in listOf(true, false)) for (confirmed in listOf(true, false)) for (kind in OccasionKind.entries) {
            if (ask && confirmed) continue // never together
            val tag = WishTag(ask, Long.MAX_VALUE, kind, 2030, confirmed)
            assertEquals(tag, WishTag.decode(tag.encode()))
            assertEquals(!ask && !confirmed, tag.unattended)
        }
    }

    @Test
    fun `broadcast tag golden strings and ids containing colons`() {
        assertEquals("broadcast:b-1:0", BroadcastTag("b-1", 0).encode())
        assertEquals("broadcast:a:b:c:12", BroadcastTag("a:b:c", 12).encode())
        assertEquals(BroadcastTag("a:b:c", 12), BroadcastTag.decode("broadcast:a:b:c:12"))
        assertEquals(BroadcastTag("550e8400-e29b-41d4-a716-446655440000", 3), BroadcastTag.decode("broadcast:550e8400-e29b-41d4-a716-446655440000:3"))
    }

    @Test
    fun `broadcast tag decode rejects near misses`() {
        listOf("broadcast:", "broadcast::1", "broadcast:id:", "broadcast:id:-1", "broadcast:id:x", "Broadcast:id:1", "rule-uuid", "broadcast:id")
            .forEach { assertNull(BroadcastTag.decode(it), it) }
        assertFalse(BroadcastTag.isBroadcast("birthday:ask:1:BIRTHDAY:2026"))
        assertFalse(BroadcastTag.isBroadcast(null))
        // A tag prefix is claimed even if malformed, so the executor never treats it as a rule id.
        assertTrue(BroadcastTag.isBroadcast("broadcast:garbage"))
    }

    @Test
    fun `tags of different kinds never decode as each other`() {
        val wish = WishTag(true, 1, OccasionKind.BIRTHDAY, 2026).encode()
        val broadcast = BroadcastTag("x", 1).encode()
        assertNull(BroadcastTag.decode(wish))
        assertNull(WishTag.decode(broadcast))
    }

    @Test
    fun `broadcast terms need acceptance until the current version is accepted`() {
        assertTrue(BroadcastTerms.needsAcceptance(null))
        assertTrue(BroadcastTerms.needsAcceptance(BroadcastTerms.VERSION - 1))
        assertFalse(BroadcastTerms.needsAcceptance(BroadcastTerms.VERSION))
        assertFalse(BroadcastTerms.needsAcceptance(BroadcastTerms.VERSION + 1))
    }
}
