package app.dak.ui.conversation

import app.dak.core.model.DeliveryStatus
import app.dak.core.model.MessageBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeliveryTicksTest {

    @Test
    fun sendingBoxesShowTheClock() {
        assertEquals(TickState.SENDING, DeliveryTicks.stateOf(MessageBox.OUTBOX, DeliveryStatus.PENDING))
        assertEquals(TickState.SENDING, DeliveryTicks.stateOf(MessageBox.QUEUED, DeliveryStatus.NONE))
    }

    @Test
    fun sentIsSingleUntilTheReportArrives() {
        assertEquals(TickState.SENT, DeliveryTicks.stateOf(MessageBox.SENT, DeliveryStatus.NONE))
        assertEquals(TickState.SENT, DeliveryTicks.stateOf(MessageBox.SENT, DeliveryStatus.PENDING))
        assertEquals(TickState.DELIVERED, DeliveryTicks.stateOf(MessageBox.SENT, DeliveryStatus.DELIVERED))
    }

    @Test
    fun sendAndDeliveryFailuresShowTheError() {
        assertEquals(TickState.FAILED, DeliveryTicks.stateOf(MessageBox.FAILED, DeliveryStatus.NONE))
        assertEquals(TickState.FAILED, DeliveryTicks.stateOf(MessageBox.FAILED, DeliveryStatus.DELIVERED))
        assertEquals(TickState.FAILED, DeliveryTicks.stateOf(MessageBox.SENT, DeliveryStatus.FAILED))
    }

    @Test
    fun incomingAndDraftsHaveNoTicks() {
        assertNull(DeliveryTicks.stateOf(MessageBox.INBOX, DeliveryStatus.DELIVERED))
        assertNull(DeliveryTicks.stateOf(MessageBox.DRAFT, DeliveryStatus.NONE))
    }
}
