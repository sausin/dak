package app.dak.ui.conversation

import app.dak.telephony.carrier.CarrierMessagingConfig
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pixel bounds for MMS images, from the carrier's `maxImageWidth` / `maxImageHeight` (`CarrierMessagingConfig`,
 * AOSP defaults 640×480) and Dak's own cap of [MAX_EDGE_PX]. Pure, for tests.
 *
 * The carrier values are applied regardless of orientation, as a long-edge / short-edge box: a 640×480 limit lets a
 * portrait photo be 480×640. (Carriers publish one landscape pair; AOSP's resizer treats it the same way.) Values
 * below [MIN_CARRIER_EDGE_PX] are treated as a misconfiguration and raised to it.
 */
object MmsImageBounds {
    /** Dak's longest edge for any MMS image, whatever the carrier allows. */
    const val MAX_EDGE_PX: Int = 1600

    /** Smallest edge a carrier limit is taken at (a 1-pixel or 0 limit would make every photo unsendable). */
    const val MIN_CARRIER_EDGE_PX: Int = 160

    /** The box an image must fit in: its longer side ≤ [longEdge], its shorter side ≤ [shortEdge]. */
    data class Box(val longEdge: Int, val shortEdge: Int)

    /** Dak's box when nothing else is known (no carrier limit). */
    val DEFAULT: Box = Box(MAX_EDGE_PX, MAX_EDGE_PX)

    fun box(carrierMaxWidth: Int, carrierMaxHeight: Int): Box {
        val w = carrierMaxWidth.takeIf { it > 0 } ?: MAX_EDGE_PX
        val h = carrierMaxHeight.takeIf { it > 0 } ?: MAX_EDGE_PX
        val long = max(w, h).coerceIn(MIN_CARRIER_EDGE_PX, MAX_EDGE_PX)
        val short = min(w, h).coerceIn(MIN_CARRIER_EDGE_PX, long)
        return Box(long, short)
    }

    /** Size of a [width]×[height] image scaled down (never up) to fit [box], keeping its aspect ratio. */
    fun targetSize(width: Int, height: Int, box: Box): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val scale = scale(width, height, box)
        if (scale >= 1f) return width to height
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /** Scale factor (≤ 1) that fits [width]×[height] into [box]. */
    fun scale(width: Int, height: Int, box: Box): Float {
        val long = max(width, height).toFloat()
        val short = min(width, height).toFloat()
        return minOf(1f, box.longEdge / long, box.shortEdge / short)
    }

    /**
     * Largest power-of-two `inSampleSize` for decoding a [width]×[height] source that still decodes at least as large
     * as its [targetSize] in [box] (so the final downscale is a smooth one, not a sub-sampled one).
     */
    fun sampleSize(width: Int, height: Int, box: Box): Int {
        val (tw, th) = targetSize(width, height, box)
        var sample = 1
        while (width / (sample * 2) >= tw && height / (sample * 2) >= th && sample < 1 shl 10) sample *= 2
        return sample
    }

    /** Longest edge below which the compressor stops shrinking a photo that still does not fit the size budget. */
    fun minEdge(box: Box, floor: Int): Int = min(floor, box.longEdge / 2)
}

/**
 * How much of the carrier's MMS allowance each attachment may use, and the pixel box photos must fit (pure, for
 * tests): [perPartBytes] is 90 % of `maxMessageSize` (headroom for SMIL, part headers and the PDU header) minus the
 * text, split evenly across the attachments, never below 16 KB; [imageBox] is the carrier's `maxImageWidth` ×
 * `maxImageHeight` ([MmsImageBounds]).
 */
internal data class MmsPartBudget(val perPartBytes: Int, val imageBox: MmsImageBounds.Box) {
    companion object {
        const val HEADROOM = 0.9
        const val MIN_PART_BUDGET = 16 * 1024

        fun of(config: CarrierMessagingConfig, textBytes: Int, attachmentCount: Int): MmsPartBudget {
            val limit = (config.maxMessageSizeBytes * HEADROOM).toInt() - textBytes
            val perPart = (limit / attachmentCount.coerceAtLeast(1)).coerceAtLeast(MIN_PART_BUDGET)
            return MmsPartBudget(perPart, MmsImageBounds.box(config.maxImageWidth, config.maxImageHeight))
        }
    }
}
