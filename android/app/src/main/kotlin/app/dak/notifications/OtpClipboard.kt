package app.dak.notifications

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import app.dak.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Puts a verification code on the clipboard, for the notification's Copy action and for auto-copy on arrival.
 *
 * Writing the clipboard from the background is allowed on every Android version (only reads are restricted since
 * Android 10); the write still runs on the main thread, where UI code calls [ClipboardManager] and where OEM
 * implementations that post to a Handler expect it. The clip is flagged sensitive so Android 13+ does not preview the
 * code in its copy overlay or keyboard suggestions (the key is a plain string below 33, where it is harmless).
 *
 * The clip is not cleared later: reading it back from the background is not allowed, so Dak could not tell whether
 * the user has copied something else since, and clearing blindly could wipe that.
 */
object OtpClipboard {

    private const val TAG = "DakNotify"

    /** Bound on waiting for the main thread, so a busy UI thread never holds up the notification. */
    private const val MAIN_THREAD_TIMEOUT_MILLIS = 400L

    /** Copies [code] from any thread; false when the main thread was busy or the system refused the clip. */
    suspend fun copyFromBackground(context: Context, code: String): Boolean =
        withTimeoutOrNull(MAIN_THREAD_TIMEOUT_MILLIS) {
            withContext(Dispatchers.Main.immediate) { copy(context, code) }
        } ?: false

    /** Copies [code]; call on the main thread. False when the system refused the clip (some OEM or work profiles). */
    fun copy(context: Context, code: String): Boolean {
        if (code.isEmpty()) return false
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
            val clip = ClipData.newPlainText(context.getString(R.string.clip_label_code), code)
            clip.description.extras = PersistableBundle().apply {
                putBoolean(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE",
                    true,
                )
            }
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "clipboard write failed: ${e.javaClass.simpleName}")
            false
        }
    }
}
