package app.dak.telephony.internal

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Log tag for the module. Never log message bodies or full addresses. */
internal const val TAG = "DakTelephony"

internal fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** A usable subscription id (not INVALID (-1) and not DEFAULT_SUBSCRIPTION_ID (Int.MAX_VALUE)). */
internal fun isUsableSubId(subId: Int): Boolean = subId >= 0 && subId != Int.MAX_VALUE

internal object SmsManagers {
    /**
     * The SmsManager for [subId]. API 31+: `getSystemService(SmsManager).createForSubscriptionId`; below:
     * `SmsManager.getSmsManagerForSubscriptionId`. Unusable ids fall back to the default SMS subscription.
     */
    @Suppress("DEPRECATION")
    fun forSubscription(context: Context, subId: Int): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            if (isUsableSubId(subId)) base.createForSubscriptionId(subId) else base
        } else {
            if (isUsableSubId(subId)) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }
}

internal object PendingIntentFlags {
    /**
     * For result PendingIntents whose Intent the platform fills in (SMS delivery reports need the `pdu` and
     * `format` extras; sent intents carry `errorCode`; MMS results carry `EXTRA_MMS_DATA` / HTTP status). They
     * must be FLAG_MUTABLE on API 31+ or the fill-in extras are dropped. Safe because every such Intent is
     * explicit (component set), which Android 14's implicit-mutable restriction requires.
     */
    val mutableResult: Int
        get() = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
}

// --- Cursor / ContentResolver helpers --------------------------------------------------------------------------

/** Column value or [default] when the column is missing (OEM schemas differ) or NULL. */
internal fun Cursor.long(column: String, default: Long = 0L): Long {
    val i = getColumnIndex(column)
    return if (i >= 0 && !isNull(i)) getLong(i) else default
}

internal fun Cursor.int(column: String, default: Int = 0): Int {
    val i = getColumnIndex(column)
    return if (i >= 0 && !isNull(i)) getInt(i) else default
}

internal fun Cursor.string(column: String): String? {
    val i = getColumnIndex(column)
    return if (i >= 0 && !isNull(i)) getString(i) else null
}

internal fun Cursor.hasColumn(column: String): Boolean = getColumnIndex(column) >= 0

/** `query` that returns null instead of throwing (SecurityException when not the SMS app, OEM SQL errors). */
internal fun ContentResolver.safeQuery(
    uri: Uri,
    projection: Array<String>? = null,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    sortOrder: String? = null,
): Cursor? = try {
    query(uri, projection, selection, selectionArgs, sortOrder)
} catch (e: Exception) {
    Log.w(TAG, "query failed for ${uri.authority}${uri.path}: ${e.javaClass.simpleName}")
    null
}

/** Converts a pure provider row (Int/Long/String/Boolean values) to ContentValues. */
internal fun Map<String, Any>.toContentValues(): ContentValues {
    val values = ContentValues(size)
    for ((key, value) in this) {
        when (value) {
            is Int -> values.put(key, value)
            is Long -> values.put(key, value)
            is String -> values.put(key, value)
            is Boolean -> values.put(key, if (value) 1 else 0)
            is ByteArray -> values.put(key, value)
            else -> values.put(key, value.toString())
        }
    }
    return values
}

/**
 * Inserts [values], retrying once without [optionalColumn] when the provider rejects it (some OEM Telephony
 * providers have no `sub_id` column).
 */
internal fun ContentResolver.insertTolerant(uri: Uri, values: ContentValues, optionalColumn: String): Uri? {
    return try {
        insert(uri, values)
    } catch (e: Exception) {
        if (!values.containsKey(optionalColumn)) {
            Log.w(TAG, "insert failed for ${uri.path}: ${e.javaClass.simpleName}")
            return null
        }
        values.remove(optionalColumn)
        try {
            insert(uri, values)
        } catch (e2: Exception) {
            Log.w(TAG, "insert failed for ${uri.path} (retry): ${e2.javaClass.simpleName}")
            null
        }
    }
}

/** Update that returns 0 instead of throwing, retrying once without [optionalColumn] if given. */
internal fun ContentResolver.updateTolerant(uri: Uri, values: ContentValues, optionalColumn: String? = null): Int {
    return try {
        update(uri, values, null, null)
    } catch (e: Exception) {
        if (optionalColumn != null && values.containsKey(optionalColumn)) {
            values.remove(optionalColumn)
            try {
                update(uri, values, null, null)
            } catch (e2: Exception) {
                0
            }
        } else {
            Log.w(TAG, "update failed for ${uri.path}: ${e.javaClass.simpleName}")
            0
        }
    }
}
