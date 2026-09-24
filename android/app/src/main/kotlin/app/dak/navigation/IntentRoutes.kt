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
                composeRoute(intent, data)
            }
            // SEND may carry an sms:/smsto: data URI (recipients) next to EXTRA_TEXT / EXTRA_STREAM.
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE ->
                composeRoute(intent, intent.data?.takeIf { it.scheme?.lowercase() in smsSchemes })
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
                // SENDTO with EXTRA_STREAM is outside the platform contract, but gallery and camera apps send it.
                Intent.ACTION_SEND, Intent.ACTION_SENDTO -> listOfNotNull(streamExtra(intent))
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

    /** Compose route from the RFC 5724 URI (if any) and the `address` / `sms_body` / `EXTRA_TEXT` extras. */
    private fun composeRoute(intent: Intent, smsUri: Uri?): String {
        val request = SmsUriParser.resolve(
            encodedSsp = smsUri?.let(::encodedSsp),
            addressExtra = runCatching { intent.getStringExtra("address") }.getOrNull(),
            smsBodyExtra = runCatching { intent.getStringExtra("sms_body") }.getOrNull(),
            // getCharSequenceExtra also returns plain String extras (String is a CharSequence).
            textExtra = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull(),
        )
        return Routes.compose(to = request.to, body = request.body)
    }

    /**
     * The URI's scheme-specific part, still percent-encoded, so [SmsUriParser] can split on `?`, `&` and `,` before
     * decoding once. `Uri.schemeSpecificPart` is already decoded and must not be used. RFC 5724 has no fragment,
     * so an unencoded `#` is data and is put back.
     */
    private fun encodedSsp(uri: Uri): String =
        uri.encodedSchemeSpecificPart.orEmpty() + (uri.encodedFragment?.let { "#$it" } ?: "")

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
