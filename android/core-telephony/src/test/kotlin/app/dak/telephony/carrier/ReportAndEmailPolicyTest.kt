package app.dak.telephony.carrier

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Report enablement ([ReportPolicy]), the new carrier keys, and e-mail recipients in [SendModePolicy]. */
class ReportAndEmailPolicyTest {

    private fun config(vararg pairs: Pair<String, Any?>) = CarrierMessagingConfig.fromLookup(mapOf(*pairs)::get)

    private val defaults = CarrierMessagingConfig.DEFAULTS

    @Test
    fun reportKeysDefaultLikeAosp() {
        val c = config()
        assertTrue(c.smsDeliveryReportsEnabled)
        assertFalse(c.mmsDeliveryReportsEnabled)
        assertFalse(c.mmsReadReportsEnabled)
        assertNull(c.emailGatewayNumber)
    }

    @Test
    fun reportKeysAndGatewayAreRead() {
        val c = config(
            "enableSMSDeliveryReports" to false,
            "enableMMSDeliveryReports" to true,
            "enableMMSReadReports" to true,
            "emailGatewayNumber" to " 6245 ",
        )
        assertFalse(c.smsDeliveryReportsEnabled)
        assertTrue(c.mmsDeliveryReportsEnabled)
        assertTrue(c.mmsReadReportsEnabled)
        assertEquals("6245", c.emailGatewayNumber)
    }

    @Test
    fun malformedGatewayNumbersAreIgnored() {
        assertNull(config("emailGatewayNumber" to "").emailGatewayNumber)
        assertNull(config("emailGatewayNumber" to "tel:6245").emailGatewayNumber)
        assertNull(config("emailGatewayNumber" to "+").emailGatewayNumber)
        assertNull(config("emailGatewayNumber" to "1".repeat(21)).emailGatewayNumber)
        assertNull(config("emailGatewayNumber" to 6245).emailGatewayNumber)
        assertEquals("+16245", config("emailGatewayNumber" to "+16245").emailGatewayNumber)
    }

    @Test
    fun reportsNeedTheUserAndTheCarrier() {
        val all = defaults.copy(smsDeliveryReportsEnabled = true, mmsDeliveryReportsEnabled = true, mmsReadReportsEnabled = true)
        val none = defaults.copy(smsDeliveryReportsEnabled = false, mmsDeliveryReportsEnabled = false, mmsReadReportsEnabled = false)
        assertTrue(ReportPolicy.requestSmsDeliveryReport(true, all))
        assertFalse(ReportPolicy.requestSmsDeliveryReport(false, all))
        assertFalse(ReportPolicy.requestSmsDeliveryReport(true, none))
        assertTrue(ReportPolicy.requestMmsDeliveryReport(true, all))
        assertFalse(ReportPolicy.requestMmsDeliveryReport(true, defaults), "AOSP default: MMS delivery reports off")
        assertTrue(ReportPolicy.requestMmsReadReport(true, all))
        assertFalse(ReportPolicy.requestMmsReadReport(false, all), "read receipts are opt-in")
        assertFalse(ReportPolicy.sendMmsReadReport(true, none))
        assertTrue(ReportPolicy.sendMmsReadReport(true, all))
        assertFalse(ReportPolicy.sendMmsReadReport(false, all))
        assertTrue(ReportPolicy.reportAllowed(true))
        assertFalse(ReportPolicy.reportAllowed(false))
    }

    private fun plan(
        recipients: Int = 1,
        emails: Int = 0,
        segments: Int = 1,
        media: Boolean = false,
        config: CarrierMessagingConfig = defaults,
    ) = SendModePolicy.plan(recipients, segments, 10, 10, media, config, emailRecipients = emails)

    @Test
    fun emailRecipientsNeedMmsWithoutAGateway() {
        assertEquals(SendPlan(SendMode.MMS), plan(emails = 1))
        assertEquals(SendMode.MMS, plan(recipients = 2, emails = 1).mode)
        assertEquals(SendMode.MMS_PER_RECIPIENT, plan(recipients = 2, emails = 1, config = defaults.copy(groupMmsEnabled = false)).mode)
        assertEquals(SendBlock.MMS_DISABLED, plan(emails = 1, config = defaults.copy(mmsEnabled = false)).block)
    }

    @Test
    fun plainTextToOneEmailUsesTheCarrierGateway() {
        val gw = defaults.copy(emailGatewayNumber = "6245")
        assertEquals(SendPlan(SendMode.SMS, emailGateway = "6245"), plan(emails = 1, config = gw))
        // Even when the carrier has MMS off.
        assertEquals("6245", plan(emails = 1, config = gw.copy(mmsEnabled = false)).emailGateway)
        // Media, long text or several recipients still need MMS.
        assertEquals(SendPlan(SendMode.MMS), plan(emails = 1, media = true, config = gw))
        assertEquals(SendMode.MMS, plan(emails = 1, segments = 30, config = gw).mode)
        assertNull(plan(recipients = 2, emails = 1, config = gw).emailGateway)
        // No e-mail recipient: the gateway is irrelevant.
        assertEquals(SendPlan(SendMode.SMS), plan(config = gw))
    }

    @Test
    fun emailHelpers() {
        assertTrue(SendModePolicy.isEmailAddress("a@b.com"))
        assertTrue(SendModePolicy.isEmailAddress(" user@example.org "))
        assertFalse(SendModePolicy.isEmailAddress("+15551234567"))
        assertFalse(SendModePolicy.isEmailAddress("@b.com"))
        assertFalse(SendModePolicy.isEmailAddress("a@"))
        assertFalse(SendModePolicy.isEmailAddress("VM-HDFCBK"))
        assertEquals("a@b.com Hello there", SendModePolicy.emailGatewayBody(" a@b.com", "Hello there"))
    }
}
