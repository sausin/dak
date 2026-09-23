package app.dak.automations.broadcast

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BroadcastPlannerTest {
    private val now = 1_700_000_000_000L
    private val planner = BroadcastPlanner()

    private fun m(address: String, name: String = "") = Member(displayName = name, address = address)

    @Test
    fun dedupesNumbersAcrossFormats() {
        val plan = planner.plan(
            BroadcastRequest(
                members = listOf(m("+91 98765 43210", "Anita Rao"), m("098765-43210"), m("919876543210"), m("9876500000")),
                template = "Hi",
                nowMillis = now,
            ),
        )
        assertEquals(listOf("+91 98765 43210", "9876500000"), plan.copies.map { it.member.address })
        assertEquals(List(2) { ExclusionReason.DUPLICATE }, plan.excluded.map { it.reason })
    }

    @Test
    fun dropsBlockedOwnShortCodesAlphanumericAndRefused() {
        val plan = planner.plan(
            BroadcastRequest(
                members = listOf(
                    m("9811111111"), m("9822222222"), m("56161"), m("VM-HDFCBK"), m("9833333333"), m("9844444444"), m("--"),
                ),
                template = "Hi",
                nowMillis = now,
                ownNumbers = listOf("+919822222222"),
                isBlocked = { it == "9833333333" },
                isRefusedDestination = { it == "9844444444" },
            ),
        )
        assertEquals(listOf("9811111111"), plan.copies.map { it.member.address })
        assertEquals(
            listOf(
                ExclusionReason.OWN_NUMBER, ExclusionReason.SHORT_CODE, ExclusionReason.ALPHANUMERIC,
                ExclusionReason.BLOCKED, ExclusionReason.SPECIAL_TARIFF, ExclusionReason.INVALID,
            ),
            plan.excluded.map { it.reason },
        )
        assertTrue(plan.canSend)
    }

    @Test
    fun rendersPlaceholdersPerRecipient() {
        val plan = planner.plan(
            BroadcastRequest(
                members = listOf(m("9811111111", "Dr. Anita Rao"), m("9822222222"), m("9833333333", "9833333333")),
                template = "Hi {firstName}! Dinner at ours on Sunday?",
                nowMillis = now,
            ),
        )
        assertEquals(
            listOf("Hi Anita! Dinner at ours on Sunday?", "Hi! Dinner at ours on Sunday?", "Hi! Dinner at ours on Sunday?"),
            plan.copies.map { it.text },
        )
        assertEquals("Dear Dr. Anita Rao, see you", BroadcastPlanner.render("Dear {name}, see you", m("1", "Dr. Anita Rao")))
    }

    @Test
    fun refusesOverTheHardCapInsteadOfTruncating() {
        val members = (0 until 51).map { m("98${(10_000_000 + it)}") }
        val plan = planner.plan(BroadcastRequest(members = members, template = "Hi", nowMillis = now))
        assertFalse(plan.canSend)
        assertTrue(plan.problems.contains(PlanProblem.TooManyRecipients(51, 50)))
        assertEquals(51, plan.copies.size)
    }

    @Test
    fun capCannotBeRaised() {
        val limits = BroadcastLimits(maxRecipients = 500, maxMessagesPerDay = 10_000)
        assertEquals(BroadcastLimits.HARD_MAX_RECIPIENTS, limits.maxRecipients)
        assertEquals(BroadcastLimits.HARD_MAX_MESSAGES_PER_DAY, limits.maxMessagesPerDay)
        assertEquals(10, BroadcastLimits(maxRecipients = 10).maxRecipients)
    }

    @Test
    fun enforcesDailyQuota() {
        val members = (0 until 30).map { m("98${(10_000_000 + it)}") }
        val plan = planner.plan(BroadcastRequest(members = members, template = "Hi", nowMillis = now, usedToday = 80))
        assertEquals(listOf<PlanProblem>(PlanProblem.DailyLimit(30, 20, 100)), plan.problems)
        val ok = planner.plan(BroadcastRequest(members = members.take(20), template = "Hi", nowMillis = now, usedToday = 80))
        assertTrue(ok.canSend)
    }

    @Test
    fun emptyMessageAndNoRecipients() {
        val plan = planner.plan(BroadcastRequest(members = listOf(m("VM-ABC")), template = "  ", nowMillis = now))
        assertEquals(listOf(PlanProblem.EmptyMessage, PlanProblem.NoRecipients), plan.problems)
        assertFalse(plan.canSend)
    }

    @Test
    fun scheduledTimeMustBeInWindow() {
        val members = listOf(m("9811111111"))
        val past = planner.plan(BroadcastRequest(members, "Hi", now, scheduledAtMillis = now - 10 * 60_000))
        assertEquals(listOf<PlanProblem>(PlanProblem.ScheduledInPast), past.problems)
        val far = planner.plan(BroadcastRequest(members, "Hi", now, scheduledAtMillis = now + 31 * BroadcastLimits.DAY_MILLIS))
        assertTrue(far.problems.single() is PlanProblem.ScheduledTooFar)
        val ok = planner.plan(BroadcastRequest(members, "Hi", now, scheduledAtMillis = now + 3_600_000))
        assertTrue(ok.canSend)
        assertEquals(now + 3_600_000, ok.copies.single().sendAtMillis)
    }

    @Test
    fun spreadsSendsAndReportsEta() {
        val members = (0 until 50).map { m("98${(10_000_000 + it)}") }
        val plan = planner.plan(BroadcastRequest(members = members, template = "Hi", nowMillis = now))
        val times = plan.copies.map { it.sendAtMillis }
        assertEquals(times.sorted(), times)
        assertEquals(now, plan.startAtMillis)
        // 10 per 10 minutes: 5 batches, last at +40 min.
        assertEquals(40 * 60_000L, plan.spreadMillis)
        assertEquals(5, times.distinct().size)
        assertWindowRespected(times)
    }

    @Test
    fun largeListNeedsExtraConfirmation() {
        val members = (0 until 25).map { m("98${(10_000_000 + it)}") }
        val plan = planner.plan(BroadcastRequest(members = members, template = "Happy Diwali from all of us!", nowMillis = now))
        assertTrue(plan.requiresExtraConfirmation)
        val small = planner.plan(BroadcastRequest(members = members.take(5), template = "Happy Diwali from all of us!", nowMillis = now))
        assertFalse(small.requiresExtraConfirmation)
    }

    private fun assertWindowRespected(times: List<Long>, max: Int = 30, window: Long = 30 * 60_000L) {
        for (t in times) {
            val inWindow = times.count { it > t - window && it <= t }
            assertTrue(inWindow <= max, "window ending $t holds $inWindow sends")
        }
    }
}
