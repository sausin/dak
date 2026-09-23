package app.dak.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.dak.MainActivity

/**
 * Translates incoming activity intents (launcher, notification taps, SENDTO/SEND from other apps) into routes.
 */
object IntentRoutes {
    /** Extra carrying a concrete route (from [Routes] builders) to open. */
    const val EXTRA_ROUTE = "app.dak.extra.ROUTE"

    /** Action of the static "Report fraud" launcher shortcut (res/xml/shortcuts.xml). */
    const val ACTION_REPORT_FRAUD = "app.dak.action.REPORT_FRAUD"

    private val smsSchemes = setOf("sms", "smsto", "mms", "mmsto")

    /** Intent that opens MainActivity at [route] (used by notifications, widgets, shortcuts). */
    fun open(context: Context, route: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            // A unique data URI keeps PendingIntents for different routes distinct.
            .setData(Uri.parse("dak://route/" + Uri.encode(route)))
            .putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Route for [intent], or null when it is a plain launch. Media shares are reported via [sharedStreams]. */
    fun routeFor(intent: Intent?): String? {
        intent ?: return null
        intent.getStringExtra(EXTRA_ROUTE)?.let { return it }
        return when (intent.action) {
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                val data = intent.data ?: return null
                if (data.scheme?.lowercase() !in smsSchemes) return null
                val (to, uriBody) = parseSmsUri(data)
                Routes.compose(to = to, body = bodyExtra(intent) ?: uriBody)
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val address = intent.getStringExtra("address")
                Routes.compose(to = address, body = bodyExtra(intent))
            }
            ACTION_REPORT_FRAUD -> Routes.fraudHelp()
            else -> null
        }
    }

    /** Media URIs shared with ACTION_SEND / ACTION_SEND_MULTIPLE (empty for text shares). */
    fun sharedStreams(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        return when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamExtra(intent))
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra(intent)
            else -> emptyList()
        }
    }

    private fun bodyExtra(intent: Intent): String? =
        intent.getStringExtra("sms_body")
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)

    /** `smsto:+9198…,+9199…?body=hello` → ("+9198…,+9199…", "hello"). */
    internal fun parseSmsUri(uri: Uri): Pair<String?, String?> {
        val ssp = uri.schemeSpecificPart.orEmpty()
        val q = ssp.indexOf('?')
        val recipients = (if (q >= 0) ssp.substring(0, q) else ssp).trim().removePrefix("//")
        val body = if (q >= 0) {
            ssp.substring(q + 1).split('&')
                .firstOrNull { it.startsWith("body=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.let { Uri.decode(it) }
        } else null
        val to = recipients.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
        return (to.ifEmpty { null }) to body
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
}
