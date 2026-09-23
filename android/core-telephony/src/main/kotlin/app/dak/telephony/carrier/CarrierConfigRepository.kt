package app.dak.telephony.carrier

import android.content.Context
import android.os.BaseBundle
import android.os.SystemClock
import android.telephony.CarrierConfigManager
import android.util.Log
import app.dak.telephony.internal.SmsManagers
import app.dak.telephony.internal.TAG
import app.dak.telephony.internal.isUsableSubId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-subscription [CarrierMessagingConfig]. Source order:
 * 1. `CarrierConfigManager.getConfigForSubId(subId)` (the supported API; needs READ_PHONE_STATE, which Dak asks for
 *    during onboarding);
 * 2. `SmsManager.getCarrierConfigValues()` for that subscription (deprecated, but readable by the default SMS app
 *    without READ_PHONE_STATE, and it carries the same MMS keys);
 * 3. [CarrierMessagingConfig.DEFAULTS].
 *
 * Cheap to call on every keystroke: results are cached per subscription for [TTL_MILLIS] (carrier config only changes
 * on SIM swaps and carrier updates). Never throws.
 */
@Singleton
class CarrierConfigRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private class Cached(val config: CarrierMessagingConfig, val atMillis: Long)

    private val cache = ConcurrentHashMap<Int, Cached>()

    fun forSubscription(subId: Int): CarrierMessagingConfig {
        val now = SystemClock.elapsedRealtime()
        cache[subId]?.takeIf { now - it.atMillis < TTL_MILLIS }?.let { return it.config }
        val config = load(subId)
        cache[subId] = Cached(config, now)
        return config
    }

    /** Drops cached values (e.g. after `CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED` or a SIM change). */
    fun invalidate() = cache.clear()

    private fun load(subId: Int): CarrierMessagingConfig {
        fromCarrierConfigManager(subId)?.let { return it }
        fromSmsManager(subId)?.let { return it }
        return CarrierMessagingConfig.DEFAULTS
    }

    private fun fromCarrierConfigManager(subId: Int): CarrierMessagingConfig? = try {
        val manager = context.getSystemService(CarrierConfigManager::class.java)
        val bundle = when {
            manager == null -> null
            isUsableSubId(subId) -> manager.getConfigForSubId(subId)
            else -> manager.config
        }
        bundle?.let { b -> CarrierMessagingConfig.fromLookup { key -> b.value(key) } }
    } catch (e: SecurityException) {
        null // READ_PHONE_STATE not granted: fall back to the SmsManager copy.
    } catch (e: RuntimeException) {
        Log.w(TAG, "carrier config unavailable: ${e.javaClass.simpleName}")
        null
    }

    @Suppress("DEPRECATION")
    private fun fromSmsManager(subId: Int): CarrierMessagingConfig? = try {
        SmsManagers.forSubscription(context, subId).carrierConfigValues
            ?.let { b -> CarrierMessagingConfig.fromLookup { key -> b.value(key) } }
    } catch (e: RuntimeException) {
        null
    }

    private companion object {
        const val TTL_MILLIS = 5 * 60_000L

        @Suppress("DEPRECATION")
        fun BaseBundle.value(key: String): Any? = if (containsKey(key)) get(key) else null
    }
}
