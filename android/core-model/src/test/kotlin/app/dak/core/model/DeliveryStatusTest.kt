package app.dak.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeliveryStatusTest {

    @Test
    fun codesMirrorProviderStatusValues() {
        assertEquals(-1, DeliveryStatus.NONE.code)
        assertEquals(0, DeliveryStatus.DELIVERED.code)
        assertEquals(32, DeliveryStatus.PENDING.code)
        assertEquals(64, DeliveryStatus.FAILED.code)
        for (s in DeliveryStatus.entries) assertEquals(s, DeliveryStatus.fromCode(s.code))
        assertEquals(DeliveryStatus.NONE, DeliveryStatus.fromCode(7))
    }

    @Test
    fun finalStates() {
        assertTrue(DeliveryStatus.DELIVERED.isFinal)
        assertTrue(DeliveryStatus.FAILED.isFinal)
        assertFalse(DeliveryStatus.PENDING.isFinal)
        assertFalse(DeliveryStatus.NONE.isFinal)
    }

    @Test
    fun groupIsDeliveredOnlyWhenEveryRecipientIs() {
        val d = DeliveryStatus.DELIVERED
        val p = DeliveryStatus.PENDING
        val f = DeliveryStatus.FAILED
        val n = DeliveryStatus.NONE
        assertEquals(d, DeliveryStatus.aggregate(listOf(d)))
        assertEquals(d, DeliveryStatus.aggregate(listOf(d, d, d)))
        assertEquals(p, DeliveryStatus.aggregate(listOf(d, p)))
        assertEquals(p, DeliveryStatus.aggregate(listOf(d, n)))
        assertEquals(f, DeliveryStatus.aggregate(listOf(d, f, p)))
        assertEquals(n, DeliveryStatus.aggregate(listOf(n, n)))
        assertEquals(n, DeliveryStatus.aggregate(emptyList()))
    }

    @Test
    fun outgoingBoxes() {
        assertTrue(MessageBox.SENT.isOutgoing)
        assertTrue(MessageBox.OUTBOX.isOutgoing)
        assertTrue(MessageBox.QUEUED.isOutgoing)
        assertTrue(MessageBox.FAILED.isOutgoing)
        assertFalse(MessageBox.INBOX.isOutgoing)
        assertFalse(MessageBox.DRAFT.isOutgoing)
    }

    @Test
    fun oldSerializedMessagesStillDecode() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """{"providerId":5,"kind":"SMS","threadId":2,"address":"+1","body":"hi","dateMillis":10}"""
        val m = json.decodeFromString(Message.serializer(), old)
        assertEquals(DeliveryStatus.NONE, m.deliveryStatus)
        assertEquals(null, m.deliveredAtMillis)
        val round = json.decodeFromString(Message.serializer(), json.encodeToString(Message.serializer(), m.copy(deliveryStatus = DeliveryStatus.DELIVERED)))
        assertEquals(DeliveryStatus.DELIVERED, round.deliveryStatus)
    }
}
