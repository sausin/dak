package app.dak.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import app.dak.telephony.carrier.CarrierConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares attachments for MMS on a background dispatcher: images are downscaled to the carrier's maximum image
 * width/height for the sending SIM ([imageBox], never above 1600 px) and re-encoded as JPEG until they fit the
 * carrier's MMS size limit ([messageLimitBytes]), leaving headroom for the text part and PDU headers. Both limits
 * come from the SIM's `CarrierConfigManager` MMS config ([CarrierConfigRepository], 300 KB and 640×480 by default).
 * Video and audio over the limit are transcoded down to it ([MmsMediaTranscoder]). The original stays wherever it
 * came from (gallery/camera), so the thread shows the full-quality copy while the network gets the compressed one.
 */
@Singleton
class MmsMediaCompressor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val carrierConfig: CarrierConfigRepository,
) {

    private val transcoder = MmsMediaTranscoder(context)

    /** Bytes the whole MMS may use on [subId]: the carrier's `maxMessageSize` (300 KB when unknown). */
    fun messageLimitBytes(subId: Int): Int = carrierConfig.forSubscription(subId).maxMessageSizeBytes

    /** The pixel box an image sent from [subId] must fit: the carrier's `maxImageWidth` × `maxImageHeight`. */
    fun imageBox(subId: Int): MmsImageBounds.Box =
        carrierConfig.forSubscription(subId).let { MmsImageBounds.box(it.maxImageWidth, it.maxImageHeight) }

    /**
     * Returns bytes for one attachment within [budgetBytes]: JPEG-compressed within [box] for images; verbatim for
     * anything else that fits; video and audio over the budget are transcoded ([MmsMediaTranscoder]: H.264 + AAC in
     * MP4, or AAC in MP4). Null when nothing fits (for example a clip too long for this carrier's limit) or the file
     * cannot be read.
     */
    suspend fun prepare(uri: Uri, mimeType: String, budgetBytes: Int, box: MmsImageBounds.Box = MmsImageBounds.DEFAULT): Prepared? = withContext(Dispatchers.IO) {
        if (mimeType.startsWith("image/") && mimeType != "image/gif") {
            // Shared URIs come from other apps: a provider may throw, lie about the type or serve garbage.
            runCatching { compressImage(uri, budgetBytes, box) }.getOrNull()?.let { return@withContext Prepared("image/jpeg", it) }
        }
        val raw = runCatching { context.contentResolver.openInputStream(uri)?.use { readAtMost(it, budgetBytes) } }.getOrNull()
        if (raw != null) return@withContext Prepared(mimeType, raw)
        if (mimeType.startsWith("video/") || mimeType.startsWith("audio/")) {
            // Blocking codec work on this IO thread; the transcoder never throws and gives up after its deadline.
            transcoder.transcode(uri, mimeType, budgetBytes)?.let { return@withContext Prepared(it.mimeType, it.bytes) }
        }
        null
    }

    /**
     * Reads at most [limit] bytes; null when the stream is longer (a hostile provider could otherwise stream
     * gigabytes into memory).
     */
    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > limit) return null
            out.write(buffer, 0, n)
        }
    }

    /** Compresses an in-memory bitmap (camera preview fallback) within [box]. */
    suspend fun prepare(bitmap: Bitmap, budgetBytes: Int, box: MmsImageBounds.Box = MmsImageBounds.DEFAULT): ByteArray? =
        withContext(Dispatchers.Default) { encodeWithin(bitmap, budgetBytes, box) }

    data class Prepared(val mimeType: String, val bytes: ByteArray)

    private fun compressImage(uri: Uri, budgetBytes: Int, box: MmsImageBounds.Box): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decompression-bomb guard: refuse absurd dimensions before any pixels are allocated.
        if (bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_SOURCE_PIXELS) return null
        val sample = MmsImageBounds.sampleSize(bounds.outWidth, bounds.outHeight, box)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val rotated = rotateForExif(uri, decoded)
        return encodeWithin(rotated, budgetBytes, box).also { if (rotated !== decoded) decoded.recycle() }
    }

    private fun encodeWithin(source: Bitmap, budgetBytes: Int, box: MmsImageBounds.Box): ByteArray? {
        var bitmap = scaleToBox(source, box)
        val minEdge = MmsImageBounds.minEdge(box, MIN_EDGE_PX)
        repeat(MAX_ROUNDS) {
            for (quality in QUALITIES) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= budgetBytes) return out.toByteArray()
            }
            val nextEdge = (max(bitmap.width, bitmap.height) * 0.75f).roundToInt()
            if (nextEdge < minEdge) return null
            bitmap = scaleToEdge(bitmap, nextEdge)
        }
        return null
    }

    /** Downscales [bitmap] to fit [box] (either orientation), keeping its aspect ratio. */
    private fun scaleToBox(bitmap: Bitmap, box: MmsImageBounds.Box): Bitmap {
        val (w, h) = MmsImageBounds.targetSize(bitmap.width, bitmap.height, box)
        if (w == bitmap.width && h == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun scaleToEdge(bitmap: Bitmap, edge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= edge) return bitmap
        val scale = edge.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
    }

    private fun rotateForExif(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: return bitmap
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val MAX_SOURCE_PIXELS = 200L * 1000 * 1000
        const val MIN_EDGE_PX = 320
        const val MAX_ROUNDS = 6
        val QUALITIES = intArrayOf(85, 70, 55, 40)
    }
}
