package app.dak.telephony.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import app.dak.telephony.di.TelephonyEntryPoint
import app.dak.telephony.di.TelephonyEntryPoints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Total budget for receiver work after `goAsync()`. Broadcasts are killed (ANR) at 10 s; we stay well under,
 * leaving headroom for slow provider writes on low-end devices.
 */
internal const val RECEIVER_BUDGET_MILLIS: Long = 7_000L

/**
 * Runs [block] off the main thread under `goAsync()`, always calling `finish()`, bounded by [budgetMillis].
 * Dependencies come from [TelephonyEntryPoint] (manifest receivers are created by the system, not injected).
 */
internal fun BroadcastReceiver.runAsync(
    context: Context,
    budgetMillis: Long = RECEIVER_BUDGET_MILLIS,
    block: suspend (TelephonyEntryPoint) -> Unit,
) {
    val name = javaClass.simpleName
    val entry = TelephonyEntryPoints.get(context)
    val pending = goAsync()
    entry.telephonyScope().launch {
        try {
            val done = withTimeoutOrNull(budgetMillis) { block(entry) }
            if (done == null) Log.w(TAG, "$name ran out of time")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$name failed", e)
        } finally {
            pending.finish()
        }
    }
}
