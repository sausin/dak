package app.dak.telephony.sim

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import app.dak.core.model.SimInfo
import app.dak.telephony.SimRepository
import app.dak.telephony.internal.SubscriptionExtras
import app.dak.telephony.internal.TAG
import app.dak.telephony.internal.hasPermission
import app.dak.telephony.internal.isUsableSubId
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [SimRepository] over SubscriptionManager. Active SIMs come first (slot order); SIMs seen before but no longer
 * present follow with `isActive = false` (remembered in SharedPreferences), so old messages keep a greyed-out
 * chip. Without READ_PHONE_STATE the list is empty; call [refresh] after the permission is granted.
 * Hot-swap and eSIM profile changes are picked up through an OnSubscriptionsChangedListener.
 */
@Singleton
class TelephonySimRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SimRepository {

    private val prefs = context.getSharedPreferences("dak_sims", Context.MODE_PRIVATE)
    private val state = MutableStateFlow<List<SimInfo>>(emptyList())
    private var listenerRegistered = false

    override val sims: StateFlow<List<SimInfo>> = state.asStateFlow()

    init {
        refresh()
        Handler(Looper.getMainLooper()).post { registerListener() }
    }

    override fun sim(subId: Int): SimInfo? = state.value.firstOrNull { it.subId == subId }

    override fun defaultSmsSubId(): Int = SubscriptionExtras.defaultSmsSubId()

    override fun isRoaming(subId: Int): Boolean = try {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val forSub = if (tm != null && isUsableSubId(subId)) tm.createForSubscriptionId(subId) else tm
        forSub?.isNetworkRoaming ?: false
    } catch (e: Exception) {
        false
    }

    @Synchronized
    override fun refresh() {
        if (!context.hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            state.value = emptyList()
            return
        }
        val active = activeSubscriptions().map { toSimInfo(it) }.sortedBy { if (it.slotIndex < 0) Int.MAX_VALUE else it.slotIndex }
        val activeIds = active.map { it.subId }.toSet()
        val remembered = SimInfoCodec.decode(prefs.getString(KEY_KNOWN, null))
        val removed = remembered
            .filter { it.subId !in activeIds }
            .map { it.copy(isActive = false, slotIndex = -1) }
        val known = (active + removed).take(MAX_REMEMBERED)
        prefs.edit().putString(KEY_KNOWN, SimInfoCodec.encode(known)).apply()
        state.value = known
    }

    @SuppressLint("MissingPermission") // READ_PHONE_STATE checked by the caller
    private fun activeSubscriptions(): List<SubscriptionInfo> = try {
        context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun toSimInfo(info: SubscriptionInfo): SimInfo {
        val slot = info.simSlotIndex
        return SimInfo(
            subId = info.subscriptionId,
            slotIndex = slot,
            displayName = info.displayName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${slot + 1}",
            carrierName = info.carrierName?.toString()?.takeIf { it.isNotBlank() },
            countryIso = info.countryIso?.takeIf { it.isNotBlank() }?.lowercase(),
            colorArgb = info.iconTint,
            number = phoneNumber(info),
            isEmbedded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.isEmbedded,
            isActive = true,
        )
    }

    /** API 33+: SubscriptionManager.getPhoneNumber (needs READ_PHONE_NUMBERS); older: SubscriptionInfo.number. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun phoneNumber(info: SubscriptionInfo): String? = try {
        val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.hasPermission(Manifest.permission.READ_PHONE_NUMBERS)) {
                context.getSystemService(SubscriptionManager::class.java)?.getPhoneNumber(info.subscriptionId)
            } else {
                null
            }
        } else {
            info.number
        }
        number?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** Must run on a Looper thread: older OnSubscriptionsChangedListener constructors bind to the current Looper. */
    @Suppress("DEPRECATION")
    private fun registerListener() {
        if (listenerRegistered) return
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                refresh()
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
            } else {
                manager.addOnSubscriptionsChangedListener(listener)
            }
            listenerRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "cannot listen for SIM changes: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val KEY_KNOWN = "known_sims"
        const val MAX_REMEMBERED = 12
    }
}
