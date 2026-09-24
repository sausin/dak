package app.dak.automation

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.RelayChannel
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncognitoAutomationPolicyTest {

    @Test
    fun actionsThatCopyTheMessageElsewhereAreBlockedInIncognitoChats() {
        listOf(
            ActionSpec.ForwardSms("+15550100"),
            ActionSpec.Webhook("https://hooks.example", secretRef = "k"),
            ActionSpec.RelayToWebClient(),
            ActionSpec.RelayRule("+15550100", RelayChannel.SMS),
        ).forEach { assertTrue(it.toString(), IncognitoAutomationPolicy.blocks(it, incognito = true)) }
    }

    @Test
    fun onDeviceActionsAndRepliesStillRun() {
        listOf(
            ActionSpec.Label("x"), ActionSpec.Archive, ActionSpec.Notify(), ActionSpec.Delete, ActionSpec.ScheduleReply("Busy", 1),
            ActionSpec.LaunchIntent("a://b"), ActionSpec.Unknown("speak", JsonObject(emptyMap())),
        ).forEach { assertFalse(it.toString(), IncognitoAutomationPolicy.blocks(it, incognito = true)) }
    }

    @Test
    fun nothingIsBlockedOutsideIncognito() {
        assertFalse(IncognitoAutomationPolicy.blocks(ActionSpec.ForwardSms("+15550100"), incognito = false))
    }
}
