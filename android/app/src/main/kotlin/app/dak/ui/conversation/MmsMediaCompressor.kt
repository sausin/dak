package app.dak.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.telephony.SmsManager
import android.media.ExifInterface
import app.dak.core.model.NO_SUB_ID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prepares attachments for MMS on a background dispatcher: images are downscaled and re-encoded as JPEG until they
 * fit the carrier's MMS size limit for the sending SIM (from `SmsManager.getCarrierConfigValues`, default 300 KB),
 * leaving headroom for the text part and PDU headers. Video and audio over the limit are transcoded down to it
 * ([MmsMediaTranscoder]). The original stays wherever it came from (gallery/camera),
 * so the thread shows the full-quality copy while the network gets the compressed one.
 */
@Singleton
class MmsMediaCompressor @Inject constructor(@ApplicationContext private val context: Context) {

    private val transcoder = MmsMediaTranscoder(context)

    /**
     * Bytes the whole MMS may use on [subId].
     *
     * TODO(carrier-config): this reads `SmsManager.getCarrierConfigValues()` directly. When the telephony work stream
     * lands its CarrierConfigManager-backed MMS config, take the limit (and max image width/height) from there; the
     * transcoder and image path already take the budget as a parameter, so only this function changes.
     */
    fun messageLimitBytes(subId: Int): Int {
        val carrier = runCatching {
            @Suppress("DEPRECATION")
            val manager = if (subId != NO_SUB_ID) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
            manager.carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 0)
        }.getOrDefault(0)
        return if (carrier > 0) carrier else DEFAULT_LIMIT_BYTES
    }

    /**
     * Returns bytes for one attachment within [budgetBytes]: JPEG-compressed for images; verbatim for anything else
     * that fits; video and audio over the budget are transcoded ([MmsMediaTranscoder]: H.264 + AAC in MP4, or AAC in
     * MP4). Null when nothing fits (for example a clip too long for this carrier's limit) or the file cannot be read.
     */
    suspend fun prepare(uri: Uri, mimeType: String, budgetBytes: Int): Prepared? = withContext(Dispatchers.IO) {
        if (mimeType.startsWith("image/") && mimeType != "image/gif") {
            // Shared URIs come from other apps: a provider may throw, lie about the type or serve garbage.
            runCatching { compressImage(uri, budgetBytes) }.getOrNull()?.let { return@withContext Prepared("image/jpeg", it) }
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

    /** Compresses an in-memory bitmap (camera preview fallback). */
    suspend fun prepare(bitmap: Bitmap, budgetBytes: Int): ByteArray? = withContext(Dispatchers.Default) {
        encodeWithin(bitmap, budgetBytes)
    }

    data class Prepared(val mimeType: String, val bytes: ByteArray)

    private fun compressImage(uri: Uri, budgetBytes: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decompression-bomb guard: refuse absurd dimensions before any pixels are allocated.
        if (bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_SOURCE_PIXELS) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val rotated = rotateForExif(uri, decoded)
        return encodeWithin(rotated, budgetBytes).also { if (rotated !== decoded) decoded.recycle() }
    }

    private fun encodeWithin(source: Bitmap, budgetBytes: Int): ByteArray? {
        var bitmap = scaleToEdge(source, MAX_EDGE_PX)
        repeat(MAX_ROUNDS) {
            for (quality in QUALITIES) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= budgetBytes) return out.toByteArray()
            }
            val nextEdge = (max(bitmap.width, bitmap.height) * 0.75f).roundToInt()
            if (nextEdge < MIN_EDGE_PX) return null
            bitmap = scaleToEdge(bitmap, nextEdge)
        }
        return null
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
        const val DEFAULT_LIMIT_BYTES = 300 * 1024
        const val MAX_EDGE_PX = 1600
        const val MAX_SOURCE_PIXELS = 200L * 1000 * 1000
        const val MIN_EDGE_PX = 320
        const val MAX_ROUNDS = 6
        val QUALITIES = intArrayOf(85, 70, 55, 40)
    }
}
