package app.dak.ui.conversation

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import app.dak.R

/**
 * Copies [text] to the clipboard. OTPs are flagged sensitive (Android 13+ hides the preview), and below 13 a
 * toast confirms the copy since the system shows none.
 */
fun copyToClipboard(context: Context, text: String, sensitive: Boolean) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(context.getString(if (sensitive) R.string.clip_label_code else R.string.scr_clip_label_message), text)
    if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    manager.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.toast_code_copied, if (sensitive) text else context.getString(R.string.scr_clip_label_message)), Toast.LENGTH_SHORT).show()
    }
}
