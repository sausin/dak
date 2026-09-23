package app.dak.automation

import app.dak.automations.broadcast.BroadcastTag
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lead time, ids, grouping, action checks and the emergency refusal behind the scheduled-message heads-up. */
class ScheduledSendHeadsUpPolicyTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val minute = 60_000L

    private fun at(hour: Int, minute: Int = 0, day: Int = 10): Long =
        LocalDateTime.of(2026, 9, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    // ---------------------------------------------------------------- lead time

    @Test
    fun `heads-up comes the lead time before the send`() {
        assertEquals(at(8, 45), HeadsUpTiming.headsUpAt(at(9), 15, HeadsUpSubject.MESSAGE, zone))
        assertEquals(at(8, 55), HeadsUpTiming.headsUpAt(at(9), 5, HeadsUpSubject.MESSAGE, zone))
        assertEquals(at(8), HeadsUpTiming.headsUpAt(at(9), 60, HeadsUpSubject.BROADCAST, zone))
    }

    @Test
    fun `off or unwanted subjects get no heads-up`() {
        assertNull(HeadsUpTiming.headsUpAt(at(9), null, HeadsUpSubject.MESSAGE, zone))
        assertNull(HeadsUpTiming.headsUpAt(at(9), 15, HeadsUpSubject.NONE, zone))
    }

    @Test
    fun `an automatic birthday wish later in the day gets a morning heads-up`() {
        assertEquals(at(8), HeadsUpTiming.headsUpAt(at(18), 15, HeadsUpSubject.AUTOMATIC_BIRTHDAY, zone))
        // Early wishes: the lead time alone is earlier than 08:00.
        assertEquals(at(7, 50), HeadsUpTiming.headsUpAt(at(8, 5), 15, HeadsUpSubject.AUTOMATIC_BIRTHDAY, zone))
        assertEquals(at(23, 45, day = 9), HeadsUpTiming.headsUpAt(at(0, 0), 15, HeadsUpSubject.AUTOMATIC_BIRTHDAY, zone))
        // Off means off, for birthdays too.
        assertNull(HeadsUpTiming.headsUpAt(at(18), null, HeadsUpSubject.AUTOMATIC_BIRTHDAY, zone))
    }

    @Test
    fun `subjects by origin`() {
        assertEquals(HeadsUpSubject.MESSAGE, HeadsUpSubject.of(ScheduledSendOrigin.USER))
        assertEquals(HeadsUpSubject.MESSAGE, HeadsUpSubject.of(ScheduledSendOrigin.AUTO_REPLY))
        assertEquals(HeadsUpSubject.BROADCAST, HeadsUpSubject.of(ScheduledSendOrigin.BROADCAST))
        assertEquals(HeadsUpSubject.AUTOMATIC_BIRTHDAY, HeadsUpSubject.of(ScheduledSendOrigin.BIRTHDAY_AUTO))
        // The "Ask me first" prompt is that wish's only notification.
        assertEquals(HeadsUpSubject.NONE, HeadsUpSubject.of(ScheduledSendOrigin.BIRTHDAY_ASK))
        assertEquals(HeadsUpSubject.NONE, HeadsUpSubject.of(ScheduledSendOrigin.BIRTHDAY_CONFIRMED))
        assertEquals(HeadsUpSubject.NONE, HeadsUpSubject.of(ScheduledSendOrigin.AUTO_FORWARD))
    }

    @Test
    fun `late heads-ups are posted now unless less than two minutes are left`() {
        val send = at(9)
        val headsUp = send - 15 * minute
        assertEquals(HeadsUpDecision.WaitUntil(headsUp), HeadsUpTiming.decide(headsUp, send, send - 30 * minute))
        assertEquals(HeadsUpDecision.NotifyNow, HeadsUpTiming.decide(headsUp, send, headsUp))
        // Scheduled 5 minutes ahead with a 15-minute lead: notify now.
        assertEquals(HeadsUpDecision.NotifyNow, HeadsUpTiming.decide(headsUp, send, send - 5 * minute))
        assertEquals(HeadsUpDecision.NotifyNow, HeadsUpTiming.decide(headsUp, send, send - HeadsUpTiming.MIN_NOTICE_MILLIS))
        assertEquals(HeadsUpDecision.Skip, HeadsUpTiming.decide(headsUp, send, send - HeadsUpTiming.MIN_NOTICE_MILLIS + 1))
        assertEquals(HeadsUpDecision.Skip, HeadsUpTiming.decide(headsUp, send, send + minute))
        assertEquals(HeadsUpDecision.Skip, HeadsUpTiming.decide(null, send, send - 30 * minute))
    }

    // ---------------------------------------------------------------- ids

    @Test
    fun `keys and notification ids are stable and derived from the send id`() {
        assertEquals("s:42", HeadsUpKeys.of(42, null))
        assertEquals("s:42", HeadsUpKeys.of(42, ScheduledSendScheduler.AUTO_REPLY_TAG))
        assertEquals(HeadsUpKeys.notificationId("s:42"), HeadsUpKeys.notificationId(HeadsUpKeys.forSend(42)))
        assertNotEquals(HeadsUpKeys.notificationId("s:42"), HeadsUpKeys.notificationId("s:43"))
        assertEquals(42L, HeadsUpKeys.sendIdOf("s:42"))
        assertNull(HeadsUpKeys.sendIdOf("b:x"))
        assertEquals("s:42", HeadsUpKeys.keyOfTag(HeadsUpKeys.tagOf("s:42")))
        assertNull(HeadsUpKeys.keyOfTag("birthday"))
        assertNull(HeadsUpKeys.keyOfTag(HeadsUpKeys.SUMMARY_TAG))
        assertNull(HeadsUpKeys.keyOfTag(null))
    }

    @Test
    fun `every copy of a broadcast shares one heads-up`() {
        val first = HeadsUpKeys.of(10, BroadcastTag("bc-1", 0).encode())
        val second = HeadsUpKeys.of(11, BroadcastTag("bc-1", 1).encode())
        assertEquals(first, second)
        assertEquals("bc-1", HeadsUpKeys.broadcastIdOf(first))
        assertNotEquals(first, HeadsUpKeys.of(12, BroadcastTag("bc-2", 0).encode()))
    }

    @Test
    fun `notification ids stay in their own range`() {
        for (key in listOf("s:0", "s:1", "s:${Long.MAX_VALUE}", "s:-5", "b:abc", "b:${"z".repeat(40)}")) {
            val id = HeadsUpKeys.notificationId(key)
            assertTrue(id in 70_000 until 110_000, "$key -> $id")
            assertNotEquals(HeadsUpKeys.SUMMARY_ID, id)
        }
    }

    // ---------------------------------------------------------------- grouping

    private fun item(id: Long, sendAt: Long, lead: Int? = 15) =
        HeadsUpItem("s:$id", sendAt, sendAt, lead?.let { sendAt - it * minute })

    @Test
    fun `a due heads-up is posted once, with a wakeup for the next`() {
        val now = at(8, 50)
        val items = listOf(item(1, at(9)), item(2, at(12)))
        val plan = HeadsUpPlanner.plan(items, emptyMap(), emptySet(), now)
        assertEquals(listOf(HeadsUpPost("s:1", silent = false)), plan.post)
        assertEquals(setOf("s:1"), plan.announced.keys)
        assertEquals(at(11, 45), plan.nextWakeAtMillis)
        assertTrue(plan.summaryKeys.isEmpty(), "one heads-up needs no summary")

        // Next look: already showing, nothing posted again.
        val again = HeadsUpPlanner.plan(items, plan.announced, setOf("s:1"), now + minute)
        assertTrue(again.post.isEmpty())
        assertTrue(again.cancel.isEmpty())
    }

    @Test
    fun `a heads-up the user swiped away is not posted again`() {
        val now = at(8, 50)
        val items = listOf(item(1, at(9)))
        val first = HeadsUpPlanner.plan(items, emptyMap(), emptySet(), now)
        val later = HeadsUpPlanner.plan(items, first.announced, visible = emptySet(), nowMillis = now + minute)
        assertTrue(later.post.isEmpty())
        assertEquals(first.announced, later.announced)
    }

    @Test
    fun `sent, cancelled or moved sends lose their heads-up`() {
        val now = at(8, 50)
        val announced = mapOf("s:1" to HeadsUpAnnounced(at(9), false), "s:2" to HeadsUpAnnounced(at(9, 5), false))
        // s:1 was sent (gone), s:2 was delayed by an hour (new version, heads-up due again later).
        val moved = item(2, at(10, 5))
        val plan = HeadsUpPlanner.plan(listOf(moved), announced, setOf("s:1", "s:2"), now)
        assertEquals(setOf("s:1", "s:2"), plan.cancel)
        assertTrue(plan.post.isEmpty())
        assertTrue(plan.announced.isEmpty())
        assertEquals(at(9, 50), plan.nextWakeAtMillis)
        // At the new time it is announced again.
        val laterPlan = HeadsUpPlanner.plan(listOf(moved), plan.announced, emptySet(), at(9, 50))
        assertEquals(listOf(HeadsUpPost("s:2", silent = false)), laterPlan.post)
    }

    @Test
    fun `turning heads-ups off takes them all down`() {
        val announced = mapOf("s:1" to HeadsUpAnnounced(at(9), false))
        val plan = HeadsUpPlanner.plan(listOf(item(1, at(9), lead = null)), announced, setOf("s:1"), at(8, 50))
        assertEquals(setOf("s:1"), plan.cancel)
        assertTrue(plan.announced.isEmpty())
        assertNull(plan.nextWakeAtMillis)
    }

    @Test
    fun `two or more showing get a summary, soonest first`() {
        val now = at(8, 50)
        val items = listOf(item(2, at(9, 3)), item(1, at(9)))
        val plan = HeadsUpPlanner.plan(items, emptyMap(), emptySet(), now)
        assertEquals(listOf("s:1", "s:2"), plan.summaryKeys)
        assertEquals(listOf("s:1", "s:2"), plan.post.map { it.key })
        assertFalse(plan.summaryAlerts)
    }

    @Test
    fun `beyond five only the summary lists them`() {
        val now = at(8, 55)
        val items = (1L..8L).map { item(it, at(9, it.toInt())) }
        val plan = HeadsUpPlanner.plan(items, emptyMap(), emptySet(), now)
        assertEquals((1L..5L).map { "s:$it" }, plan.post.map { it.key })
        assertEquals((1L..8L).map { "s:$it" }, plan.summaryKeys)
        assertTrue(plan.summaryAlerts, "heads-ups that went straight into the summary alert through it")
        assertEquals(setOf("s:6", "s:7", "s:8"), plan.announced.filterValues { it.collapsed }.keys)

        // s:1 goes out: s:6 moves from the summary into its own notification, silently.
        val visible = (1L..5L).map { "s:$it" }.toSet()
        val next = HeadsUpPlanner.plan(items.drop(1), plan.announced, visible, at(9, 1))
        assertEquals(listOf(HeadsUpPost("s:6", silent = true)), next.post)
        assertEquals(setOf("s:1"), next.cancel)
        assertFalse(next.summaryAlerts)
        assertEquals((2L..8L).map { "s:$it" }, next.summaryKeys)
    }

    @Test
    fun `an earlier send pushes a showing heads-up into the summary`() {
        val now = at(9, 0)
        val shown = (1L..5L).map { item(it, at(9, 10 + it.toInt())) }
        val first = HeadsUpPlanner.plan(shown, emptyMap(), emptySet(), now)
        assertEquals(5, first.post.size)
        val visible = shown.map { it.key }.toSet()
        val earlier = item(9, at(9, 5))
        val plan = HeadsUpPlanner.plan(shown + earlier, first.announced, visible, now)
        assertEquals(listOf(HeadsUpPost("s:9", silent = false)), plan.post)
        assertEquals(setOf("s:5"), plan.cancel)
        assertTrue(plan.announced.getValue("s:5").collapsed)
    }

    @Test
    fun `a broadcast keeps its heads-up while its copies go out`() {
        val start = at(9)
        val key = HeadsUpKeys.forBroadcast("bc")
        val broadcast = HeadsUpItem(key, start, version = start, headsUpAtMillis = start - 15 * minute)
        val first = HeadsUpPlanner.plan(listOf(broadcast), emptyMap(), emptySet(), at(8, 45))
        assertEquals(listOf(HeadsUpPost(key, silent = false)), first.post)
        // Copies keep going out after the start: same version, never announced again.
        val later = HeadsUpPlanner.plan(listOf(broadcast), first.announced, emptySet(), at(9, 20))
        assertTrue(later.post.isEmpty())
        assertEquals(first.announced, later.announced)
    }

    // ---------------------------------------------------------------- actions

    @Test
    fun `actions only act on a pending row`() {
        assertEquals(HeadsUpActionCheck.OK, HeadsUpActions.check(exists = true, pending = true))
        assertEquals(HeadsUpActionCheck.NOT_PENDING, HeadsUpActions.check(exists = true, pending = false))
        assertEquals(HeadsUpActionCheck.GONE, HeadsUpActions.check(exists = false, pending = false))
    }

    @Test
    fun `action wire names round trip`() {
        HeadsUpAction.entries.forEach { assertEquals(it, HeadsUpAction.fromWire(it.wire)) }
        assertNull(HeadsUpAction.fromWire("explode"))
        assertNull(HeadsUpAction.fromWire(null))
    }

    @Test
    fun `delays move the send later`() {
        val send = at(9)
        assertEquals(at(10), HeadsUpActions.delayedTo(HeadsUpAction.DELAY_HOUR, send, at(8, 50), zone))
        // Already late (the send is being held): an hour from now.
        assertEquals(at(10, 30), HeadsUpActions.delayedTo(HeadsUpAction.DELAY_HOUR, send, at(9, 30), zone))
        assertEquals(at(9, day = 11), HeadsUpActions.delayedTo(HeadsUpAction.DELAY_TOMORROW, send, at(8, 50), zone))
        assertNull(HeadsUpActions.delayedTo(HeadsUpAction.SEND_NOW, send, at(8, 50), zone))
        assertNull(HeadsUpActions.delayedTo(HeadsUpAction.CANCEL, send, at(8, 50), zone))
    }

    @Test
    fun `tomorrow same time keeps the wall-clock time across a DST change`() {
        val berlin = ZoneId.of("Europe/Berlin")
        // 25 Oct 2025 09:00 CEST -> 26 Oct 2025 09:00 CET (25 hours later).
        val send = LocalDateTime.of(2025, 10, 25, 9, 0).atZone(berlin).toInstant().toEpochMilli()
        val next = LocalDateTime.of(2025, 10, 26, 9, 0).atZone(berlin).toInstant().toEpochMilli()
        assertEquals(next, HeadsUpActions.delayedTo(HeadsUpAction.DELAY_TOMORROW, send, send - 10 * minute, berlin))
    }

    @Test
    fun `moving a broadcast keeps the pacing between copies`() {
        assertEquals(
            listOf(at(10), at(10, 2), at(10, 4)),
            HeadsUpActions.shifted(listOf(at(9), at(9, 2), at(9, 4)), oldStartMillis = at(9), newStartMillis = at(10)),
        )
    }

    // ---------------------------------------------------------------- emergency

    @Test
    fun `scheduling to an emergency number is refused`() {
        val emergency = setOf("112", "911", "100")
        assertTrue(ScheduledEmergencyPolicy.refuses(listOf("112")) { it in emergency })
        // Any emergency recipient refuses the whole send (a group text including 100).
        assertTrue(ScheduledEmergencyPolicy.refuses(listOf("+919812345678", "100")) { it in emergency })
        assertEquals("100", ScheduledEmergencyPolicy.firstEmergency(listOf("+919812345678", "100")) { it in emergency })
        assertFalse(ScheduledEmergencyPolicy.refuses(listOf("+919812345678")) { it in emergency })
        assertFalse(ScheduledEmergencyPolicy.refuses(emptyList()) { true })
    }

    @Test
    fun `a failing emergency check does not block scheduling`() {
        assertFalse(ScheduledEmergencyPolicy.refuses(listOf("+919812345678")) { error("no telephony") })
    }
}
