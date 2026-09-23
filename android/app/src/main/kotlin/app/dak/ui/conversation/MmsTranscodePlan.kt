package app.dak.ui.conversation

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Pure sizing decisions for shrinking video and audio to fit an MMS (3GPP TS 26.140 / OMA MMS-CONF: H.264 Baseline
 * video with AAC-LC audio in an MP4 container). No Android types, so it is unit-tested on the JVM.
 *
 * The byte budget is split into a container allowance (moov tables grow with the sample count) and the elementary
 * streams. The stream bitrate is `usable bytes × 8 / duration`. Audio gets a fixed low bitrate and video the rest.
 * Resolution and frame rate step down with the video bitrate. When even the floor bitrate does not fit, the plan is
 * null and the caller refuses the attachment (the clip is too long for this carrier's limit).
 *
 * Encoders overshoot their target, so the caller checks the real output size and retries with a higher [attempt],
 * which scales the bitrates down by [RETRY_FACTOR] each time.
 */
object MmsTranscodePlan {
    /** Budget share kept free for bitrate overshoot on top of the container allowance. */
    const val SAFETY = 0.85
    const val CONTAINER_BASE_BYTES = 4 * 1024
    /** Rough per-sample cost of the MP4 sample tables (stsz, stts, stss, stco). */
    const val BYTES_PER_SAMPLE = 12
    /** AAC-LC frames per second at 48 kHz (1024 samples per frame); lower rates have fewer frames. */
    const val AUDIO_FRAMES_PER_SEC = 47
    const val MIN_VIDEO_BPS = 32_000
    const val MIN_AUDIO_BPS = 16_000
    const val MAX_AUDIO_BPS = 64_000
    const val RETRY_FACTOR = 0.7
    const val MAX_ATTEMPTS = 3
    /** Longest clip considered at all (a longer one never fits any carrier limit at a watchable rate). */
    const val MAX_DURATION_US = 10L * 60 * 1_000_000

    data class Video(
        val width: Int,
        val height: Int,
        val videoBitrate: Int,
        /** 0 when the clip is sent without sound (no audio track, or an unsupported one). */
        val audioBitrate: Int,
        val frameRate: Int,
        val iFrameIntervalSec: Int = 2,
    )

    data class Audio(val bitrate: Int)

    /**
     * Plan for a video of [durationUs] at [srcWidth]×[srcHeight] (unrotated, as the decoder outputs it), or null
     * when it cannot fit [budgetBytes].
     */
    fun video(budgetBytes: Int, durationUs: Long, srcWidth: Int, srcHeight: Int, audioChannels: Int, attempt: Int = 0): Video? {
        if (budgetBytes <= 0 || durationUs <= 0 || durationUs > MAX_DURATION_US) return null
        if (srcWidth <= 0 || srcHeight <= 0 || attempt >= MAX_ATTEMPTS) return null
        val seconds = durationUs / 1_000_000.0
        val withAudio = audioChannels in 1..2
        // Worst-case frame rate for the sample-table estimate; the real rate is decided below.
        val samples = seconds * (24 + if (withAudio) AUDIO_FRAMES_PER_SEC else 0)
        val usable = budgetBytes * SAFETY - CONTAINER_BASE_BYTES - samples * BYTES_PER_SAMPLE
        if (usable <= 0) return null
        val scale = retryScale(attempt)
        val totalBps = usable * 8 / seconds * scale
        val audioBps = if (withAudio) audioBitrateFor(audioChannels, totalBps) else 0
        val videoBps = (totalBps - audioBps).toInt()
        if (videoBps < MIN_VIDEO_BPS) return null
        val maxEdge = when {
            videoBps >= 600_000 -> 640
            videoBps >= 250_000 -> 480
            videoBps >= 120_000 -> 320
            else -> 176
        }
        val (w, h) = scaledSize(srcWidth, srcHeight, maxEdge)
        val fps = if (videoBps >= 250_000) 24 else 15
        return Video(w, h, videoBps, audioBps, fps)
    }

    /** Plan for an audio clip of [durationUs] with [channels] channels, or null when it cannot fit [budgetBytes]. */
    fun audio(budgetBytes: Int, durationUs: Long, channels: Int, attempt: Int = 0): Audio? {
        if (budgetBytes <= 0 || durationUs <= 0 || durationUs > MAX_DURATION_US) return null
        if (channels !in 1..2 || attempt >= MAX_ATTEMPTS) return null
        val seconds = durationUs / 1_000_000.0
        val usable = budgetBytes * SAFETY - CONTAINER_BASE_BYTES - seconds * AUDIO_FRAMES_PER_SEC * BYTES_PER_SAMPLE
        if (usable <= 0) return null
        val bps = (usable * 8 / seconds * retryScale(attempt)).toInt().coerceAtMost(MAX_AUDIO_BPS)
        if (bps < MIN_AUDIO_BPS) return null
        return Audio(bps)
    }

    /**
     * Scales [srcWidth]×[srcHeight] so the longer edge is at most [maxEdge] (never upscaling), keeping the aspect
     * ratio, with both sides rounded down to a multiple of 16 (the safest size for hardware AVC encoders) and at
     * least 16.
     */
    fun scaledSize(srcWidth: Int, srcHeight: Int, maxEdge: Int): Pair<Int, Int> {
        val longest = max(srcWidth, srcHeight)
        val scale = min(1.0, maxEdge.toDouble() / longest)
        fun align(v: Double): Int = max(16, (v.roundToInt() / 16) * 16)
        return align(srcWidth * scale) to align(srcHeight * scale)
    }

    /** Next frame time to keep when dropping frames down to [fps]; frames earlier than this are skipped. */
    fun nextFrameUs(keptPtsUs: Long, fps: Int): Long = keptPtsUs + (1_000_000.0 / fps * 0.9).roundToLong()

    private fun audioBitrateFor(channels: Int, totalBps: Double): Int {
        val preferred = if (channels >= 2) 32_000 else 24_000
        // Tight budgets: give audio at most a third, but never below what AAC-LC can encode.
        return min(preferred, max(MIN_AUDIO_BPS, (totalBps / 3).toInt()))
    }

    private fun retryScale(attempt: Int): Double {
        var s = 1.0
        repeat(attempt) { s *= RETRY_FACTOR }
        return s
    }
}
