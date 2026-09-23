package app.dak.telephony.carrier

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CarrierMessagingConfigTest {

    private fun config(vararg pairs: Pair<String, Any?>) = CarrierMessagingConfig.fromLookup(mapOf(*pairs)::get)

    @Test
    fun missingKeysGiveAospDefaults() {
        val c = config()
        assertEquals(CarrierMessagingConfig.DEFAULTS, c)
        assertTrue(c.mmsEnabled)
        assertTrue(c.groupMmsEnabled)
        assertEquals(300 * 1024, c.maxMessageSizeBytes)
        assertNull(c.recipientLimit)
        assertNull(c.smsToMmsSegmentThreshold)
        assertEquals(40, c.maxSubjectLength)
        assertFalse(c.notifyWapMmsc)
    }

    @Test
    fun carrierValuesAreRead() {
        val c = config(
            "enabledMMS" to true,
            "enableGroupMms" to false,
            "maxMessageSize" to 614_400,
            "recipientLimit" to 20,
            "smsToMmsTextThreshold" to 4,
            "smsToMmsTextLengthThreshold" to 500,
            "maxSubjectLength" to 80,
            "maxMessageTextSize" to 2_000,
            "maxImageWidth" to 1_280,
            "maxImageHeight" to 960,
            "enabledNotifyWapMMSC" to true,
            "sendMultipartSmsAsSeparateMessages" to true,
        )
        assertFalse(c.groupMmsEnabled)
        assertEquals(614_400, c.maxMessageSizeBytes)
        assertEquals(20, c.recipientLimit)
        assertEquals(4, c.smsToMmsSegmentThreshold)
        assertEquals(500, c.smsToMmsLengthThreshold)
        assertEquals(80, c.maxSubjectLength)
        assertEquals(2_000, c.maxTextBytes)
        assertEquals(1_280, c.maxImageWidth)
        assertEquals(960, c.maxImageHeight)
        assertTrue(c.notifyWapMmsc)
        assertTrue(c.sendMultipartSmsAsSeparateMessages)
    }

    @Test
    fun noLimitMistypedAndInsaneValuesFallBack() {
        val c = config(
            "recipientLimit" to -1,
            "smsToMmsTextThreshold" to -1,
            "maxMessageTextSize" to 0,
            "maxMessageSize" to 1_000, // below any real carrier: misconfiguration
            "enableGroupMms" to "false", // wrong type
            "maxSubjectLength" to 0,
        )
        assertNull(c.recipientLimit)
        assertNull(c.smsToMmsSegmentThreshold)
        assertNull(c.maxTextBytes)
        assertEquals(CarrierMessagingConfig.DEFAULT_MAX_MESSAGE_SIZE, c.maxMessageSizeBytes)
        assertTrue(c.groupMmsEnabled)
        assertEquals(CarrierMessagingConfig.DEFAULT_MAX_SUBJECT_LENGTH, c.maxSubjectLength)
    }

    @Test
    fun aThrowingLookupIsTreatedAsMissing() {
        assertEquals(CarrierMessagingConfig.DEFAULTS, CarrierMessagingConfig.fromLookup { throw IllegalStateException("vendor bug") })
    }
}

class SendModePolicyTest {
    private val defaults = CarrierMessagingConfig.DEFAULTS

    private fun plan(
        recipients: Int = 1,
        segments: Int = 1,
        length: Int = 10,
        bytes: Int = length,
        media: Boolean = false,
        config: CarrierMessagingConfig = defaults,
    ) = SendModePolicy.plan(recipients, segments, length, bytes, media, config)

    @Test
    fun plainTextToOnePersonIsSms() {
        assertEquals(SendPlan(SendMode.SMS), plan())
        assertEquals(SendMode.SMS, plan(segments = SendModePolicy.DEFAULT_SEGMENT_THRESHOLD).mode)
    }

    @Test
    fun mediaAndVeryLongTextAreMms() {
        assertEquals(SendMode.MMS, plan(media = true).mode)
        assertEquals(SendMode.MMS, plan(segments = SendModePolicy.DEFAULT_SEGMENT_THRESHOLD + 1).mode)
    }

    @Test
    fun carrierThresholdsReplaceTheDefault() {
        val four = defaults.copy(smsToMmsSegmentThreshold = 4)
        assertEquals(SendMode.SMS, plan(segments = 4, config = four).mode)
        assertEquals(SendMode.MMS, plan(segments = 5, config = four).mode)
        val byLength = defaults.copy(smsToMmsLengthThreshold = 300)
        assertEquals(SendMode.MMS, plan(segments = 2, length = 301, config = byLength).mode)
        assertEquals(SendMode.SMS, plan(segments = 2, length = 300, config = byLength).mode)
    }

    @Test
    fun groupGoesAsGroupMmsOnlyWhenTheCarrierAllowsIt() {
        assertEquals(SendMode.MMS, plan(recipients = 3).mode)
        val noGroup = defaults.copy(groupMmsEnabled = false)
        // Text to several people: individual SMS (one row per recipient).
        assertEquals(SendPlan(SendMode.SMS), plan(recipients = 3, config = noGroup))
        // Media to several people: one MMS each.
        assertEquals(SendPlan(SendMode.MMS_PER_RECIPIENT), plan(recipients = 3, media = true, config = noGroup))
        assertEquals(SendMode.MMS_PER_RECIPIENT, plan(recipients = 2, segments = 30, config = noGroup).mode)
    }

    @Test
    fun mmsDisabledKeepsTextAsSmsAndBlocksMedia() {
        val noMms = defaults.copy(mmsEnabled = false)
        assertEquals(SendPlan(SendMode.SMS), plan(recipients = 3, segments = 30, config = noMms))
        assertEquals(SendBlock.MMS_DISABLED, plan(media = true, config = noMms).block)
    }

    @Test
    fun recipientAndTextLimits() {
        val limited = defaults.copy(recipientLimit = 5, maxTextBytes = 1_000)
        assertNull(plan(recipients = 5, config = limited).block)
        assertEquals(SendBlock.TOO_MANY_RECIPIENTS, plan(recipients = 6, config = limited).block)
        // One MMS each: the per-message recipient limit does not apply.
        assertNull(plan(recipients = 6, media = true, config = limited.copy(groupMmsEnabled = false)).block)
        assertEquals(SendBlock.TEXT_TOO_LONG, plan(media = true, bytes = 1_001, config = limited).block)
        // The MMS text limit does not apply to SMS.
        assertNull(plan(bytes = 5_000, config = limited).block)
    }

    @Test
    fun subjectIsCutToTheCarrierLimitOnCodePoints() {
        val c = defaults.copy(maxSubjectLength = 5)
        assertEquals("Hello", SendModePolicy.subject("Hello world", c))
        assertEquals("ab😀cd", SendModePolicy.subject("ab😀cdef", c))
        assertNull(SendModePolicy.subject("   ", c))
        assertNull(SendModePolicy.subject(null, c))
        assertEquals("Hi", SendModePolicy.subject(" Hi ", c))
    }
}
