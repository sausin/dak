package app.dak.ui.fraud

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Intents used by the Report fraud screen. Calls only ever open the dialer ([Intent.ACTION_DIAL]) with the number
 * filled in: Dak never places a call itself and holds no CALL_PHONE permission.
 */
internal object FraudIntents {

    /** Opens the dialer with [number]; false when no dialer exists (e.g. tablets without telephony). */
    fun dial(context: Context, number: String): Boolean =
        start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number.trim(), null)))

    /** Opens an official https page in the browser; anything but https is refused. */
    fun openUrl(context: Context, url: String): Boolean {
        val uri = Uri.parse(url.trim())
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        return start(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
