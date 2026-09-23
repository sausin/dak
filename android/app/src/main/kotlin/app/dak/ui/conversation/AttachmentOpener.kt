package app.dak.ui.conversation

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.dak.R
import app.dak.core.model.Attachment

/**
 * Opens a message attachment safely. The MIME type of a received part is chosen by the sender, so handing it to
 * ACTION_VIEW verbatim would route `application/vnd.android.package-archive` to the package installer, `text/html`
 * to a browser, and so on. Only inert viewer types open directly ([isViewable]); anything else is offered through
 * a "Save or share" chooser as `application/octet-stream`, so the user decides where the bytes go and no handler
 * is selected by the sender's type.
 */
object AttachmentOpener {

    private val viewableExact = setOf("text/plain", "text/x-vcard", "text/vcard", "text/directory", "text/x-vcalendar", "text/calendar")

    /** True for media and plain-text/contact types that system viewers render without executing anything. */
    fun isViewable(mimeType: String): Boolean {
        val mime = mimeType.substringBefore(';').trim().lowercase()
        if (mime in viewableExact) return true
        val top = mime.substringBefore('/')
        // image/svg+xml is a document format that can carry script: not treated as a plain image.
        return (top == "image" && mime != "image/svg+xml") || top == "video" || top == "audio"
    }

    fun open(context: Context, attachment: Attachment) {
        val uri = runCatching { Uri.parse(attachment.uri) }.getOrNull() ?: return
        val mime = attachment.mimeType.substringBefore(';').trim().lowercase()
        val intent = if (isViewable(mime)) {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            val send = Intent(Intent.ACTION_SEND)
                .setType(GENERIC_TYPE)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(null, uri)
            Intent.createChooser(send, context.getString(R.string.sec_attachment_save_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Nothing can show this type; the bubble still shows its name.
        } catch (e: SecurityException) {
            // Provider refused to share the part with another app.
        }
    }

    private const val GENERIC_TYPE = "application/octet-stream"
}
