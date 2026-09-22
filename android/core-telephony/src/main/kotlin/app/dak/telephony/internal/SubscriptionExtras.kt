package app.dak.telephony.internal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.util.Log
import app.dak.core.model.NO_SUB_ID

/**
 * Reads the subscription id the platform attaches to SMS_DELIVER / WAP_PUSH_DELIVER / RESPOND_VIA_MESSAGE
 * intents. AOSP puts it under `android.telephony.extra.SUBSCRIPTION_INDEX` (SubscriptionManager
 * .EXTRA_SUBSCRIPTION_INDEX) and the legacy `subscription` key; OEMs add others, sometimes as Long or String,
 * or only a slot index. Falls back to the default SMS subscription.
 */
internal object SubscriptionExtras {
    private val SUB_ID_KEYS = listOf(
        "android.telephony.extra.SUBSCRIPTION_INDEX",
        "subscription",
        "subscription_id",
        "sub_id",
    )
    private val SLOT_KEYS = listOf("android.telephony.extra.SLOT_INDEX", "slot", "simSlot", "slot_id", "phone")

    fun subIdFrom(context: Context, intent: Intent): Int {
        val extras = intent.extras
        if (extras != null) {
            for (key in SUB_ID_KEYS) {
                val v = readInt(extras, key)
                if (v != null && isUsableSubId(v)) return v
            }
            for (key in SLOT_KEYS) {
                val slot = readInt(extras, key) ?: continue
                val sub = subIdForSlot(context, slot)
                if (sub != null) return sub
            }
        }
        return defaultSmsSubId()
    }

    fun defaultSmsSubId(): Int {
        val id = SubscriptionManager.getDefaultSmsSubscriptionId()
        return if (isUsableSubId(id)) id else NO_SUB_ID
    }

    @Suppress("DEPRECATION")
    private fun readInt(extras: Bundle, key: String): Int? {
        if (!extras.containsKey(key)) return null
        return when (val raw = extras.get(key)) {
            is Int -> raw
            is Long -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }

    @SuppressLint("MissingPermission") // checked via hasPermission
    private fun subIdForSlot(context: Context, slot: Int): Int? {
        if (slot < 0 || !context.hasPermission(Manifest.permission.READ_PHONE_STATE)) return null
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
            sm.getActiveSubscriptionInfoForSimSlotIndex(slot)?.subscriptionId?.takeIf { isUsableSubId(it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to map slot to subscription")
            null
        }
    }
}
