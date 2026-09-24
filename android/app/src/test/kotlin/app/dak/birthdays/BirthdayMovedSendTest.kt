package app.dak.birthdays

import app.dak.automations.birthdays.OccasionKind
import app.dak.automations.birthdays.WishTag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A wish delayed from its heads-up must not be pulled back (or dropped to next year) by the birthday scheduler. */
class BirthdayMovedSendTest {

    private val tag = WishTag(ask = false, contactId = 7, kind = OccasionKind.BIRTHDAY, year = 2026)
    private val configured = 1_790_000_000_000L
    private val hour = 60 * 60_000L

    private fun keep(
        pending: Boolean = true,
        sendTag: WishTag? = tag,
        contactId: Long = 7,
        kind: OccasionKind = OccasionKind.BIRTHDAY,
        configAt: Long? = configured,
        sendAt: Long,
    ) = BirthdayMovedSend.keep(pending, sendTag, contactId, kind, configAt, sendAt)

    @Test
    fun `a delayed wish is kept where the user moved it`() {
        assertTrue(keep(sendAt = configured + hour))
        assertTrue(keep(sendAt = configured + 24 * hour))
        // Moved earlier from the scheduled list.
        assertTrue(keep(sendAt = configured - 2 * hour))
    }

    @Test
    fun `an unmoved wish is reconciled as before`() {
        assertFalse(keep(sendAt = configured))
    }

    @Test
    fun `only this contact's pending wish, within a week, counts as moved`() {
        assertFalse(keep(pending = false, sendAt = configured + hour))
        assertFalse(keep(sendTag = null, sendAt = configured + hour))
        assertFalse(keep(contactId = 8, sendAt = configured + hour))
        assertFalse(keep(kind = OccasionKind.ANNIVERSARY, sendAt = configured + hour))
        assertFalse(keep(configAt = null, sendAt = configured + hour))
        assertFalse(keep(sendAt = configured + BirthdayMovedSend.MAX_MOVE_MILLIS + 1))
    }
}
