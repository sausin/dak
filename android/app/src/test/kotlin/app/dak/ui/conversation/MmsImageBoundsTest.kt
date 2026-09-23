package app.dak.ui.conversation

import app.dak.telephony.carrier.CarrierMessagingConfig
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** Carrier image dimensions and per-part byte budgets for outgoing MMS. */
class MmsImageBoundsTest {

    @Test
    fun carrierLimitsAreOrientationAgnosticAndCappedAt1600() {
        assertEquals(MmsImageBounds.Box(640, 480), MmsImageBounds.box(640, 480))
        assertEquals(MmsImageBounds.Box(640, 480), MmsImageBounds.box(480, 640))
        assertEquals(MmsImageBounds.Box(1600, 1600), MmsImageBounds.box(4000, 3000))
        assertEquals(MmsImageBounds.Box(1600, 1200), MmsImageBounds.box(1600, 1200))
        // Nonsense values: the missing side falls back to Dak's cap, tiny ones are raised to the floor.
        assertEquals(MmsImageBounds.Box(1600, 640), MmsImageBounds.box(640, 0))
        assertEquals(MmsImageBounds.Box(160, 160), MmsImageBounds.box(1, 1))
    }

    @Test
    fun imagesShrinkIntoTheBoxKeepingAspect() {
        val box = MmsImageBounds.box(640, 480)
        assertEquals(640 to 480, MmsImageBounds.targetSize(4000, 3000, box))
        assertEquals(480 to 640, MmsImageBounds.targetSize(3000, 4000, box), "portrait uses the same box turned")
        // A panorama is limited by its long edge, a square by the short edge.
        assertEquals(640 to 160, MmsImageBounds.targetSize(4000, 1000, box))
        assertEquals(480 to 480, MmsImageBounds.targetSize(2000, 2000, box))
        // Never upscaled.
        assertEquals(300 to 200, MmsImageBounds.targetSize(300, 200, box))
        assertEquals(1600 to 1200, MmsImageBounds.targetSize(4000, 3000, MmsImageBounds.DEFAULT))
    }

    @Test
    fun sampleSizeNeverDecodesBelowTheTarget() {
        val box = MmsImageBounds.box(640, 480)
        assertEquals(4, MmsImageBounds.sampleSize(4000, 3000, box)) // 1000x750 >= 640x480; 500x375 would be too small
        assertEquals(1, MmsImageBounds.sampleSize(640, 480, box))
        assertEquals(1, MmsImageBounds.sampleSize(1000, 700, box))
        for ((w, h) in listOf(12000 to 9000, 5000 to 100, 100 to 5000, 7 to 7)) {
            val s = MmsImageBounds.sampleSize(w, h, box)
            val (tw, th) = MmsImageBounds.targetSize(w, h, box)
            assertTrue(w / s >= tw && h / s >= th, "$w x $h sampled by $s is below $tw x $th")
        }
    }

    @Test
    fun minimumEdgeFollowsSmallCarrierBoxes() {
        assertEquals(320, MmsImageBounds.minEdge(MmsImageBounds.box(640, 480), 320))
        assertEquals(160, MmsImageBounds.minEdge(MmsImageBounds.box(320, 240), 320))
    }

    @Test
    fun partBudgetUsesTheCarrierSizeAndImageLimits() {
        val config = CarrierMessagingConfig.DEFAULTS.copy(maxMessageSizeBytes = 600 * 1024, maxImageWidth = 1280, maxImageHeight = 960)
        val budget = MmsPartBudget.of(config, textBytes = 1024, attachmentCount = 2)
        assertEquals(((600 * 1024 * 0.9).toInt() - 1024) / 2, budget.perPartBytes)
        assertEquals(MmsImageBounds.Box(1280, 960), budget.imageBox)
        // Many attachments on a small carrier still get the 16 KB floor.
        assertEquals(16 * 1024, MmsPartBudget.of(CarrierMessagingConfig.DEFAULTS, 0, 40).perPartBytes)
        assertEquals(MmsImageBounds.Box(640, 480), MmsPartBudget.of(CarrierMessagingConfig.DEFAULTS, 0, 1).imageBox)
    }
}
