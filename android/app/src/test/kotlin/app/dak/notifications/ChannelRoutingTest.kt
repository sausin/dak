package app.dak.notifications

import app.dak.classify.InvestmentLabels
import app.dak.core.model.Category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelRoutingTest {

    @Test
    fun `each category has its own channel`() {
        assertEquals(NotificationChannels.PERSONAL, ChannelRouting.baseChannel(Category.PERSONAL))
        assertEquals(NotificationChannels.OTP, ChannelRouting.baseChannel(Category.OTP))
        assertEquals(NotificationChannels.ALERTS, ChannelRouting.baseChannel(Category.TRANSACTION))
        assertEquals(NotificationChannels.PROMOTIONS, ChannelRouting.baseChannel(Category.PROMOTION))
        assertEquals(NotificationChannels.SPAM, ChannelRouting.baseChannel(Category.SPAM))
        assertEquals(NotificationChannels.OTHER, ChannelRouting.baseChannel(Category.UNKNOWN))
    }

    @Test
    fun `every routed channel is in the catalog`() {
        val ids = ChannelCatalog.messages.map { it.id }.toSet()
        for (category in Category.entries) {
            for (scam in listOf(false, true)) {
                for (consumed in listOf(false, true)) {
                    val channel = ChannelRouting.baseChannel(category, likelyScam = scam, otpConsumed = consumed)
                    assertTrue(channel in ids, "$category scam=$scam consumed=$consumed -> $channel")
                }
            }
        }
    }

    @Test
    fun `fake credit warnings go to alerts whatever the category`() {
        assertEquals(NotificationChannels.ALERTS, ChannelRouting.baseChannel(Category.TRANSACTION, likelyScam = true))
        assertEquals(NotificationChannels.ALERTS, ChannelRouting.baseChannel(Category.PERSONAL, likelyScam = true))
        assertEquals(NotificationChannels.ALERTS, ChannelRouting.baseChannel(Category.UNKNOWN, likelyScam = true))
    }

    @Test
    fun `investment security alerts are loud and routine investment updates quiet`() {
        val alert = setOf(InvestmentLabels.ALERT, "dlt-service")
        val update = setOf(InvestmentLabels.UPDATE)
        assertEquals(NotificationChannels.ALERTS, ChannelRouting.baseChannel(Category.TRANSACTION, labels = alert))
        assertEquals(NotificationChannels.ALERTS, ChannelRouting.baseChannel(Category.UNKNOWN, labels = alert))
        assertEquals(NotificationChannels.ALERTS, ChannelRouting.baseChannel(Category.PROMOTION, labels = alert))
        assertEquals(NotificationChannels.OTHER, ChannelRouting.baseChannel(Category.TRANSACTION, labels = update))
        // A bank's own SIP debit carries no investment label: an ordinary transaction alert.
        assertEquals(NotificationChannels.ALERTS, ChannelRouting.baseChannel(Category.TRANSACTION, labels = setOf("dlt-transactional")))
        // OTPs and spam keep their channels; a fake credit stays a fraud warning.
        assertEquals(NotificationChannels.OTP, ChannelRouting.baseChannel(Category.OTP, labels = alert))
        assertEquals(NotificationChannels.SPAM, ChannelRouting.baseChannel(Category.SPAM, labels = alert))
        assertEquals(NotificationChannels.PROMOTIONS, ChannelRouting.baseChannel(Category.PROMOTION, labels = update))
        assertEquals(NotificationChannels.ALERTS, ChannelRouting.baseChannel(Category.TRANSACTION, likelyScam = true, labels = update))
        assertTrue(ChannelRouting.isInvestmentAlert(Category.UNKNOWN, alert))
        assertFalse(ChannelRouting.isInvestmentAlert(Category.SPAM, alert))
        assertFalse(ChannelRouting.isInvestmentAlert(Category.TRANSACTION, update))
    }

    @Test
    fun `every label-routed channel is in the catalog`() {
        val ids = ChannelCatalog.messages.map { it.id }.toSet()
        for (category in Category.entries) {
            for (labels in listOf(setOf(InvestmentLabels.ALERT), setOf(InvestmentLabels.UPDATE))) {
                val channel = ChannelRouting.baseChannel(category, labels = labels)
                assertTrue(channel in ids, "$category $labels -> $channel")
            }
        }
    }

    @Test
    fun `only OTPs use the consumed channel`() {
        assertEquals(NotificationChannels.OTP_CONSUMED, ChannelRouting.baseChannel(Category.OTP, otpConsumed = true))
        assertEquals(NotificationChannels.ALERTS, ChannelRouting.baseChannel(Category.TRANSACTION, otpConsumed = true))
    }

    @Test
    fun `forCategory matches the router`() {
        for (category in Category.entries) {
            assertEquals(ChannelRouting.baseChannel(category), NotificationChannels.forCategory(category))
        }
    }

    @Test
    fun `transactions channels move to alerts keeping their SIM`() {
        assertEquals(NotificationChannels.ALERTS, ChannelCatalog.renamedTarget("transactions"))
        assertEquals("alerts.sim2", ChannelCatalog.renamedTarget("transactions.sim2"))
        assertNull(ChannelCatalog.renamedTarget(NotificationChannels.OTP))
        assertNull(ChannelCatalog.renamedTarget(NotificationChannels.ALERTS))
        assertNull(ChannelCatalog.renamedTarget(ConversationChannels.channelIdFor("transactions")))
        assertTrue("transactions" in ChannelCatalog.obsolete)
        // A retired id must never come back as a live channel: its user settings would be restored from the old one.
        assertTrue(ChannelCatalog.all.none { it.id in ChannelCatalog.obsolete })
    }
}
