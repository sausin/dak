package app.dak.telephony

import app.dak.core.model.SimInfo
import app.dak.telephony.sim.SimInfoCodec
import org.junit.Test
import kotlin.test.assertEquals

class SimInfoCodecTest {
    @Test
    fun roundTripsIncludingSeparatorsInNames() {
        val sims = listOf(
            SimInfo(subId = 1, slotIndex = 0, displayName = "Jio | Work\\Home", carrierName = "Jio", countryIso = "in", colorArgb = -16711936, number = "+919812345678", isEmbedded = false, isActive = true),
            SimInfo(subId = 7, slotIndex = -1, displayName = "Travel\neSIM", isEmbedded = true, isActive = false),
        )
        assertEquals(sims, SimInfoCodec.decode(SimInfoCodec.encode(sims)))
    }

    @Test
    fun corruptLinesAreSkipped() {
        val good = SimInfo(subId = 2, slotIndex = 1, displayName = "Airtel")
        val encoded = SimInfoCodec.encode(listOf(good)) + "\nnot|enough|fields\nx|0|a|b|c|0|d|0|1"
        assertEquals(listOf(good), SimInfoCodec.decode(encoded))
        assertEquals(emptyList(), SimInfoCodec.decode(null))
    }
}
