package app.dak.incognito

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailedSendStateTest {

    @Test
    fun firstFailureThenRetriedThenKept() {
        var state = FailedSendState()
        assertEquals(FailedSendStep.FIRST_FAILURE, state.stepOf("sms:1"))
        state = state.copy(retried = state.retried + "sms:1")
        assertEquals(FailedSendStep.SECOND_FAILURE, state.stepOf("sms:1"))
        state = state.copy(kept = state.kept + "sms:1")
        assertEquals(FailedSendStep.KEPT, state.stepOf("sms:1"))
        assertEquals(FailedSendStep.FIRST_FAILURE, state.stepOf("sms:2"))
    }

    @Test
    fun boundedKeepsTheNewestKeys() {
        val state = FailedSendState(retried = (1..10).map { "sms:$it" }.toCollection(LinkedHashSet()))
        val bounded = state.bounded(max = 3)
        assertEquals(setOf("sms:8", "sms:9", "sms:10"), bounded.retried)
        assertTrue(FailedSendState().bounded(max = 3).retried.isEmpty())
    }
}
