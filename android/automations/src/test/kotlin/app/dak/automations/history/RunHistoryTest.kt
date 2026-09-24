package app.dak.automations.history

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.RelayChannel
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunHistoryTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 7, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun run(id: Long, atMillis: Long, outcome: RunOutcome = RunOutcome.SENT, label: String? = "Sharma CA", to: String = "+919800000000") =
        RunRecord(
            id = id, ruleId = "r", ruleName = "Tax", atMillis = atMillis, messageKey = "sms:$id", conversationId = "m:HDFCBK",
            sourceLabel = "HDFCBK", actionKind = "ForwardSms", destinationLabel = label, destination = to,
            outcome = outcome, reason = null, textPreview = "Fwd",
        )

    @Test
    fun `summary counts outcomes and destinations`() {
        val runs = listOf(
            run(1, at(1, 9)),
            run(2, at(3, 9)),
            run(3, at(5, 9), RunOutcome.FAILED),
            run(4, at(9, 9), RunOutcome.SKIPPED),
            run(5, at(31, 9), label = null, to = "+917700000000"),
        )
        val s = RunHistory.summarize(runs)
        assertEquals(3, s.sent)
        assertEquals(1, s.failed)
        assertEquals(1, s.skipped)
        assertEquals(5, s.total)
        assertEquals(listOf("Sharma CA", "+917700000000"), s.destinations)
        assertEquals(at(1, 9), s.firstAtMillis)
        assertEquals(at(31, 9), s.lastAtMillis)
    }

    @Test
    fun `empty summary`() {
        val s = RunHistory.summarize(emptyList())
        assertEquals(0, s.total)
        assertNull(s.firstAtMillis)
    }

    @Test
    fun `groups by local day, newest first`() {
        // 23:30 IST on 1 Jul is 18:00 UTC: it must land on 1 Jul in the user's zone.
        val lateEvening = ZonedDateTime.of(2026, 7, 1, 23, 30, 0, 0, zone).toInstant().toEpochMilli()
        val runs = listOf(run(1, at(1, 9)), run(2, lateEvening), run(3, at(2, 8)))
        val days = RunHistory.groupByDay(runs, zone)
        assertEquals(listOf(LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 1)), days.map { it.date })
        assertEquals(listOf(2L, 1L), days[1].runs.map { it.id })
    }

    @Test
    fun `preview is one line, bounded and masks the OTP`() {
        assertEquals("a b c", RunHistory.preview("a\n b\t\tc "))
        assertEquals("Your OTP is •••••• for login", RunHistory.preview("Your OTP is 482913 for login", otpCode = "482913"))
        val long = RunHistory.preview("x".repeat(500), max = 10)
        assertEquals(10, long.length)
        assertEquals('…', long.last())
    }

    @Test
    fun `destinations are normalised`() {
        assertEquals("+911234", RunHistory.destinationOf(ActionSpec.ForwardSms(" +911234 ")))
        assertEquals("hooks.example.com", RunHistory.destinationOf(ActionSpec.Webhook("https://hooks.example.com/a?b=c", secretRef = "s")))
        assertEquals("sms:+91", RunHistory.destinationOf(ActionSpec.RelayRule("+91", RelayChannel.SMS)))
        assertEquals("VM-HDFC", RunHistory.destinationOf(ActionSpec.ScheduleReply("ok", 1), replyTo = "VM-HDFC"))
        assertEquals("https", RunHistory.destinationOf(ActionSpec.LaunchIntent("https://x.y")))
        assertNull(RunHistory.destinationOf(ActionSpec.Archive))
        assertEquals("ForwardSms", RunHistory.kindOf(ActionSpec.ForwardSms("1")))
    }

    @Test
    fun `unknown outcome names read as failed`() {
        assertEquals(RunOutcome.SKIPPED, RunOutcome.fromName("SKIPPED"))
        assertEquals(RunOutcome.FAILED, RunOutcome.fromName("???"))
    }
}
