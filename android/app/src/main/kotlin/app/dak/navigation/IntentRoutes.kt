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
    private const val DAK_SCHEME = "dak"
    private const val CONVERSATION_HOST = "conversation"

    /**
     * Intent that opens MainActivity at [route], for Dak's own PendingIntents (notifications, alarms). It carries
     * the per-install [RouteToken]; never hand it to another app or put it in a launcher shortcut (use
     * [openConversationShortcut] there).
     */
    fun open(context: Context, route: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            // A unique data URI keeps PendingIntents for different routes distinct.
            .setData(Uri.parse("dak://route/" + Uri.encode(route)))
            .putExtra(EXTRA_ROUTE, route)
            .putExtra(RouteToken.EXTRA, RouteToken.get(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * Token-free intent for a launcher conversation shortcut (launchers can read shortcut intents, so no secret):
     * `dak://conversation/<id>` may only ever open that conversation, never another screen or arguments.
     */
    fun openConversationShortcut(context: Context, conversationId: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.Builder().scheme(DAK_SCHEME).authority(CONVERSATION_HOST).appendPath(conversationId).build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * Route for [intent], or null when it is a plain launch. Media shares are reported via [sharedStreams].
     *
     * MainActivity is exported: an arbitrary [EXTRA_ROUTE] is honoured only with a valid [RouteToken] (i.e. from
     * our own PendingIntents). Other apps get just what the platform contracts allow: compose from
     * SENDTO/VIEW/SEND, the fraud-help shortcut action, and opening a conversation by id.
     */
    fun routeFor(context: Context, intent: Intent?): String? {
        intent ?: return null
        val route = runCatching { intent.getStringExtra(EXTRA_ROUTE) }.getOrNull()
        if (route != null) {
            val token = runCatching { intent.getStringExtra(RouteToken.EXTRA) }.getOrNull()
            if (RouteToken.matches(context, token)) return route
            // Forged deep link from another app: ignore the route and fall through to the public contracts.
        }
        return when (intent.action) {
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                val data = intent.data ?: return null
                if (data.scheme.equals(DAK_SCHEME, ignoreCase = true)) {
                    if (data.host != CONVERSATION_HOST) return null
                    val id = data.pathSegments.singleOrNull()?.takeIf { it.isNotBlank() } ?: return null
                    return Routes.conversation(id)
                }
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

    /**
     * Media URIs shared with ACTION_SEND / ACTION_SEND_MULTIPLE (empty for text shares), filtered by
     * [isAcceptableSharedUri] and capped at [MAX_SHARED_STREAMS].
     */
    fun sharedStreams(context: Context, intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val raw = runCatching {
            when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(streamExtra(intent))
                Intent.ACTION_SEND_MULTIPLE -> streamListExtra(intent).filterNotNull()
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        return raw.filter { isAcceptableSharedUri(context.packageName, it) }.distinct().take(MAX_SHARED_STREAMS)
    }

    /** Most attachments accepted from one share. */
    const val MAX_SHARED_STREAMS: Int = 10

    /**
     * Confused-deputy guard: the composer later reads shared URIs with *Dak's* identity (which holds READ_SMS and
     * owns private files), so another app could otherwise share `file:///data/data/app.dak/…`, one of our own
     * FileProvider URIs, or `content://mms/part/N` and get it attached to an MMS. Only `content://` URIs from other
     * apps' providers are accepted; `file://`, our own authorities (`<package>` / `<package>.*`) and the Telephony
     * provider's authorities are rejected.
     */
    internal fun isAcceptableSharedUri(packageName: String, uri: Uri): Boolean {
        if (!uri.scheme.equals("content", ignoreCase = true)) return false
        val authority = uri.authority?.lowercase()?.substringAfterLast('@')?.substringBefore(':') ?: return false
        if (authority.isEmpty()) return false
        val own = packageName.lowercase()
        if (authority == own || authority.startsWith("$own.")) return false
        return authority !in TELEPHONY_AUTHORITIES
    }

    private val TELEPHONY_AUTHORITIES = setOf("mms", "sms", "mms-sms", "telephony", "icc", "carrier_information", "service-state")

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
