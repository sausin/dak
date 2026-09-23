package app.dak.ui.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sizing of MMS video / audio transcodes ([MmsTranscodePlan]). */
class MmsTranscodePlanTest {

    private val kb300 = (300 * 1024 * 0.9).toInt()
    private val mb1 = (1024 * 1024 * 0.9).toInt()
    private fun sec(s: Double) = (s * 1_000_000).toLong()

    /** Rough stream size the plan aims for, to check it stays inside the budget. */
    private fun estimatedBytes(p: MmsTranscodePlan.Video, seconds: Double) = (p.videoBitrate + p.audioBitrate) * seconds / 8

    @Test
    fun `short phone clip fits 300 KB at low resolution with sound`() {
        val plan = assertNotNull(MmsTranscodePlan.video(kb300, sec(10.0), 1920, 1080, audioChannels = 2))
        assertTrue(plan.width <= 320 && plan.height <= 320, "size ${plan.width}x${plan.height}")
        assertEquals(0, plan.width % 16)
        assertEquals(0, plan.height % 16)
        assertTrue(plan.audioBitrate in MmsTranscodePlan.MIN_AUDIO_BPS..32_000)
        assertTrue(plan.videoBitrate >= MmsTranscodePlan.MIN_VIDEO_BPS)
        assertTrue(estimatedBytes(plan, 10.0) < kb300)
    }

    @Test
    fun `larger carrier limit gives more pixels`() {
        val small = assertNotNull(MmsTranscodePlan.video(kb300, sec(10.0), 1920, 1080, 2))
        val big = assertNotNull(MmsTranscodePlan.video(mb1, sec(10.0), 1920, 1080, 2))
        assertTrue(big.width > small.width)
        assertTrue(big.videoBitrate > small.videoBitrate)
        assertTrue(estimatedBytes(big, 10.0) < mb1)
    }

    @Test
    fun `long clip that cannot fit is refused`() {
        assertNull(MmsTranscodePlan.video(kb300, sec(120.0), 1920, 1080, 2))
        assertNull(MmsTranscodePlan.video(kb300, MmsTranscodePlan.MAX_DURATION_US + 1, 1920, 1080, 2))
    }

    @Test
    fun `bad input is refused`() {
        assertNull(MmsTranscodePlan.video(kb300, 0, 1920, 1080, 2))
        assertNull(MmsTranscodePlan.video(kb300, sec(5.0), 0, 1080, 2))
        assertNull(MmsTranscodePlan.video(0, sec(5.0), 1920, 1080, 2))
        assertNull(MmsTranscodePlan.video(2_000, sec(5.0), 1920, 1080, 2))
    }

    @Test
    fun `no or unsupported audio gives a silent plan`() {
        assertEquals(0, assertNotNull(MmsTranscodePlan.video(kb300, sec(10.0), 1280, 720, audioChannels = 0)).audioBitrate)
        assertEquals(0, assertNotNull(MmsTranscodePlan.video(kb300, sec(10.0), 1280, 720, audioChannels = 6)).audioBitrate)
    }

    @Test
    fun `retries lower the bitrate and eventually give up`() {
        val first = assertNotNull(MmsTranscodePlan.video(mb1, sec(10.0), 1920, 1080, 2, attempt = 0))
        val second = assertNotNull(MmsTranscodePlan.video(mb1, sec(10.0), 1920, 1080, 2, attempt = 1))
        assertTrue(second.videoBitrate < first.videoBitrate)
        assertNull(MmsTranscodePlan.video(mb1, sec(10.0), 1920, 1080, 2, attempt = MmsTranscodePlan.MAX_ATTEMPTS))
    }

    @Test
    fun `scaled size keeps aspect, never upscales, aligns to 16`() {
        assertEquals(320 to 176, MmsTranscodePlan.scaledSize(1920, 1080, 320))
        assertEquals(176 to 320, MmsTranscodePlan.scaledSize(1080, 1920, 320))
        assertEquals(176 to 144, MmsTranscodePlan.scaledSize(176, 144, 640))
        assertEquals(16 to 16, MmsTranscodePlan.scaledSize(10, 10, 640))
    }

    @Test
    fun `frame dropping keeps about the target rate`() {
        // 30 fps source, 15 fps target: every other frame.
        var next = Long.MIN_VALUE
        var kept = 0
        for (i in 0 until 30) {
            val pts = i * 33_333L
            if (pts >= next) {
                kept++
                next = MmsTranscodePlan.nextFrameUs(pts, 15)
            }
        }
        assertEquals(15, kept)
    }

    @Test
    fun `audio plan`() {
        val voiceNote = assertNotNull(MmsTranscodePlan.audio(kb300, sec(60.0), channels = 1))
        assertTrue(voiceNote.bitrate in MmsTranscodePlan.MIN_AUDIO_BPS..MmsTranscodePlan.MAX_AUDIO_BPS)
        assertTrue(voiceNote.bitrate * 60.0 / 8 < kb300)
        assertEquals(MmsTranscodePlan.MAX_AUDIO_BPS, assertNotNull(MmsTranscodePlan.audio(mb1, sec(10.0), 2)).bitrate)
        assertNull(MmsTranscodePlan.audio(kb300, sec(600.0), 2))
        assertNull(MmsTranscodePlan.audio(kb300, sec(10.0), 6))
    }

    @Test
    fun `every plan over a grid of inputs fits the budget with its container and never upscales`() {
        val budgets = listOf(30 * 1024, 100 * 1024, kb300, 600 * 1024, mb1, 3 * 1024 * 1024)
        val durations = listOf(0.5, 1.0, 5.0, 30.0, 90.0, 300.0, 600.0)
        val sizes = listOf(1920 to 1080, 1080 to 1920, 640 to 480, 320 to 240, 176 to 144, 100 to 50, 4000 to 16)
        var planned = 0
        for (budget in budgets) for (seconds in durations) for ((w, h) in sizes) for (channels in 0..3) for (attempt in 0 until MmsTranscodePlan.MAX_ATTEMPTS) {
            val p = MmsTranscodePlan.video(budget, sec(seconds), w, h, channels, attempt) ?: continue
            planned++
            val samples = seconds * (24 + if (channels in 1..2) MmsTranscodePlan.AUDIO_FRAMES_PER_SEC else 0)
            val projected = estimatedBytes(p, seconds) + MmsTranscodePlan.CONTAINER_BASE_BYTES + samples * MmsTranscodePlan.BYTES_PER_SAMPLE
            val case = "budget=$budget s=$seconds ${w}x$h ch=$channels attempt=$attempt -> $p"
            assertTrue(projected <= budget * MmsTranscodePlan.SAFETY + 1, case)
            assertTrue(p.videoBitrate >= MmsTranscodePlan.MIN_VIDEO_BPS, case)
            assertEquals(if (channels in 1..2) true else false, p.audioBitrate > 0, case)
            assertTrue(p.width % 16 == 0 && p.height % 16 == 0 && p.width >= 16 && p.height >= 16, case)
            assertTrue(maxOf(p.width, p.height) <= 640, case)
            assertTrue(p.width <= maxOf(16, w) && p.height <= maxOf(16, h), "never upscaled: $case")
            assertTrue(p.frameRate in setOf(15, 24), case)
        }
        assertTrue(planned > 100, "the grid must exercise real plans, got $planned")
    }

    @Test
    fun `each retry asks for strictly less`() {
        val plans = (0 until MmsTranscodePlan.MAX_ATTEMPTS).map { MmsTranscodePlan.video(mb1, sec(20.0), 1280, 720, 2, it) }
        val bitrates = plans.map { assertNotNull(it).videoBitrate + it.audioBitrate }
        assertEquals(bitrates.sortedDescending(), bitrates)
        assertEquals(bitrates.size, bitrates.distinct().size)
        assertNull(MmsTranscodePlan.video(mb1, sec(20.0), 1280, 720, 2, MmsTranscodePlan.MAX_ATTEMPTS), "no attempt past the last")
        assertNull(MmsTranscodePlan.audio(mb1, sec(20.0), 2, MmsTranscodePlan.MAX_ATTEMPTS))
    }

    @Test
    fun `nonsense inputs are refused rather than planned`() {
        assertNull(MmsTranscodePlan.video(0, sec(5.0), 640, 480, 2))
        assertNull(MmsTranscodePlan.video(-1, sec(5.0), 640, 480, 2))
        assertNull(MmsTranscodePlan.video(mb1, 0, 640, 480, 2))
        assertNull(MmsTranscodePlan.video(mb1, MmsTranscodePlan.MAX_DURATION_US + 1, 640, 480, 2))
        assertNull(MmsTranscodePlan.video(mb1, sec(5.0), 0, 480, 2))
        assertNull(MmsTranscodePlan.video(mb1, sec(5.0), 640, -1, 2))
        assertNull(MmsTranscodePlan.audio(mb1, sec(5.0), 0))
        assertNull(MmsTranscodePlan.audio(mb1, -5, 1))
    }

    @Test
    fun `frame dropping keeps a little slack so a steady source is not decimated twice`() {
        assertEquals(1_000L + 60_000L, MmsTranscodePlan.nextFrameUs(1_000, 15))
        assertEquals(37_500L, MmsTranscodePlan.nextFrameUs(0, 24))
    }
}
