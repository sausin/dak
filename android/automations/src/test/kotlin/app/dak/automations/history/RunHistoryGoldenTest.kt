package app.dak.automations.history

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.RelayChannel
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The run log is stored (and backed up): [RunOutcome] names, [SkipReason] codes and [RunHistory.kindOf] names are
 * written into rows and read back after upgrades and restores. Pinned here so a class rename cannot silently change
 * what history rows say.
 */
class RunHistoryGoldenTest {

    @Test
    fun `stored outcome names and skip reasons are pinned`() {
        assertEquals(listOf("SENT", "FAILED", "SKIPPED"), RunOutcome.entries.map { it.name })
        RunOutcome.entries.forEach { assertEquals(it, RunOutcome.fromName(it.name)) }
        assertEquals(RunOutcome.FAILED, RunOutcome.fromName(null))
        assertEquals(RunOutcome.FAILED, RunOutcome.fromName("sent"), "names are case-sensitive; unknown reads as failed")
        assertEquals(
            listOf("not_confirmed", "no_app_lock", "contact_removed", "no_contacts_access", "possible_scam", "premium_locked"),
            listOf(SkipReason.NOT_CONFIRMED, SkipReason.NO_APP_LOCK, SkipReason.CONTACT_REMOVED, SkipReason.NO_CONTACTS_ACCESS, SkipReason.POSSIBLE_SCAM, SkipReason.PREMIUM_LOCKED),
        )
    }

    @Test
    fun `stored action kind names are pinned`() {
        val kinds = listOf(
            ActionSpec.Label("x"), ActionSpec.Archive, ActionSpec.Notify(), ActionSpec.ForwardSms("+1"), ActionSpec.ScheduleReply("t", 1),
            ActionSpec.LaunchIntent("a://b"), ActionSpec.Delete, ActionSpec.Webhook("https://h", secretRef = "k"),
            ActionSpec.RelayToWebClient(), ActionSpec.RelayRule("+1", RelayChannel.SMS), ActionSpec.Unknown("speak", JsonObject(emptyMap())),
        ).map(RunHistory::kindOf)
        assertEquals(
            listOf("Label", "Archive", "Notify", "ForwardSms", "ScheduleReply", "LaunchIntent", "Delete", "Webhook", "RelayToWebClient", "RelayRule", "Unknown:speak"),
            kinds,
        )
    }

    @Test
    fun `destinations for every action type`() {
        assertEquals("+15550100", RunHistory.destinationOf(ActionSpec.ForwardSms(" +15550100 ")))
        assertEquals("+1999", RunHistory.destinationOf(ActionSpec.ScheduleReply("t", 1), replyTo = " +1999 "))
        assertEquals(null, RunHistory.destinationOf(ActionSpec.ScheduleReply("t", 1)))
        assertEquals("hooks.example.com", RunHistory.destinationOf(ActionSpec.Webhook("https://user:pw@hooks.example.com:8443/p?q=1", secretRef = "k")))
        assertEquals("not a url", RunHistory.destinationOf(ActionSpec.Webhook("not a url", secretRef = "k")), "unparseable URL is kept verbatim")
        assertEquals("mailto:x", RunHistory.destinationOf(ActionSpec.Webhook("mailto:x", secretRef = "k")), "no host: verbatim")
        assertEquals("web:p1", RunHistory.destinationOf(ActionSpec.RelayToWebClient("p1")))
        assertEquals("web", RunHistory.destinationOf(ActionSpec.RelayToWebClient()))
        assertEquals("whatsapp_one_tap:+1", RunHistory.destinationOf(ActionSpec.RelayRule(" +1 ", RelayChannel.WHATSAPP_ONE_TAP)))
        assertEquals("geo", RunHistory.destinationOf(ActionSpec.LaunchIntent("geo:0,0")))
        assertEquals("intent", RunHistory.destinationOf(ActionSpec.LaunchIntent("::bad uri")))
        assertEquals("speak", RunHistory.destinationOf(ActionSpec.Unknown("speak", JsonObject(emptyMap()))))
        listOf(ActionSpec.Label("x"), ActionSpec.Archive, ActionSpec.Notify(), ActionSpec.Delete).forEach {
            assertEquals(null, RunHistory.destinationOf(it))
        }
    }

    @Test
    fun `preview masks every occurrence of the code and never leaks it through truncation`() {
        val text = "Your OTP is 482913. Do not share 482913 with anyone."
        val p = RunHistory.preview(text, otpCode = "482913")
        assertFalse("482913" in p)
        assertEquals("Your OTP is ••••••. Do not share •••••• with anyone.", p)
        // A 3-digit code is left alone (would blank ordinary numbers); blank codes are ignored.
        assertEquals("Pay 123 now", RunHistory.preview("Pay 123 now", otpCode = "123"))
        assertEquals("a b", RunHistory.preview("a\n\t b", otpCode = " "))
        // The code near the cut is masked before cutting, so no partial code survives.
        val long = "x".repeat(150) + " 987654 tail"
        val cut = RunHistory.preview(long, otpCode = "987654", max = 155)
        assertFalse(cut.contains("9876"))
        assertTrue(cut.endsWith("…"))
        assertEquals(155, cut.length)
    }

    @Test
    fun `preview bounds hold for tiny limits`() {
        assertEquals("…", RunHistory.preview("hello", max = 1))
        assertEquals("…", RunHistory.preview("hello", max = 0))
        assertEquals("hello", RunHistory.preview("  hello  ", max = 5))
        assertEquals("", RunHistory.preview("   "))
        assertEquals(RunHistory.PREVIEW_MAX_CHARS, RunHistory.preview("y".repeat(10_000)).length)
    }

    @Test
    fun `grouping by day uses the given zone across midnight and DST`() {
        fun rec(id: Long, at: Long) = RunRecord(id, "r", "R", at, null, null, null, "ForwardSms", null, "+1", RunOutcome.SENT, null, null)
        val kolkata = ZoneId.of("Asia/Kolkata")
        // 2026-03-01T18:29:59Z is 23:59:59 in Kolkata; one second later is the next local day.
        val before = 1_772_389_799_000L
        val days = RunHistory.groupByDay(listOf(rec(1, before), rec(2, before + 1_000), rec(3, before + 1_000)), kolkata)
        assertEquals(listOf(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 1)), days.map { it.date })
        assertEquals(listOf(3L, 2L), days[0].runs.map { it.id }, "same instant: newest id first")
        // The same instants in UTC fall on one day.
        assertEquals(1, RunHistory.groupByDay(listOf(rec(1, before), rec(2, before + 1_000)), ZoneId.of("UTC")).size)
        assertEquals(emptyList(), RunHistory.groupByDay(emptyList(), kolkata))
    }

    @Test
    fun `summary ignores blank destinations and orders ties by name`() {
        fun rec(dest: String?, label: String?, outcome: RunOutcome, at: Long) =
            RunRecord(at, "r", "R", at, null, null, null, "ForwardSms", label, dest, outcome, null, null)
        val s = RunHistory.summarize(
            listOf(
                rec("+2", null, RunOutcome.SENT, 5), rec("+1", null, RunOutcome.SENT, 3), rec(null, "  ", RunOutcome.SENT, 9),
                rec("+1", "Asha", RunOutcome.SENT, 1), rec("+3", null, RunOutcome.FAILED, 7),
            ),
        )
        assertEquals(listOf("+1", "+2", "Asha"), s.destinations)
        assertEquals(4, s.sent)
        assertEquals(1, s.failed)
        assertEquals(5, s.total)
        assertEquals(1L, s.firstAtMillis)
        assertEquals(9L, s.lastAtMillis)
    }
}
