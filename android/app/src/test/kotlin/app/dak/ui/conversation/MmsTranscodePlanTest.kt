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
}
