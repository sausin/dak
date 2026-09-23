package app.dak.navigation

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Per-install secret that proves an intent carrying [IntentRoutes.EXTRA_ROUTE] was built by Dak itself (our own
 * notification / alarm PendingIntents). MainActivity is exported, so without it any installed app could deep-link
 * into any screen with arbitrary arguments. Other apps cannot read the extras of our PendingIntents, so the token
 * never leaves the process except inside them. Never put it in a shortcut (launchers can read shortcut intents).
 */
internal object RouteToken {
    private const val PREFS = "dak_route_token"
    private const val KEY = "token"
    const val EXTRA = "app.dak.extra.ROUTE_TOKEN"

    @Volatile private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY, null)
            val token = if (!existing.isNullOrEmpty()) {
                existing
            } else {
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING).also {
                    prefs.edit().putString(KEY, it).commit()
                }
            }
            cached = token
            return token
        }
    }

    /** Constant-time check of a presented token. */
    fun matches(context: Context, presented: String?): Boolean {
        if (presented.isNullOrEmpty()) return false
        return MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), get(context).toByteArray(Charsets.UTF_8))
    }
}
