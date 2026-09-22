package app.dak.telephony.blocked

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import android.util.Log
import app.dak.telephony.BlockedNumbers
import app.dak.telephony.internal.TAG
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [BlockedNumbers] over the platform's shared list (`BlockedNumberContract`), so blocks made in the Phone app apply
 * to us and ours apply to calls. Only the default SMS / dialer app (and only the primary user) may use it; every
 * call degrades to false / empty otherwise. The platform already drops SMS from blocked numbers before
 * SMS_DELIVER, so the receivers do not filter again.
 */
@Singleton
class TelephonyBlockedNumbers @Inject constructor(
    @ApplicationContext private val context: Context,
) : BlockedNumbers {

    override suspend fun isBlocked(address: String): Boolean = io(false) {
        BlockedNumberContract.isBlocked(context, address)
    }

    override suspend fun block(address: String): Boolean = io(false) {
        val values = ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, address) }
        context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values) != null
    }

    override suspend fun unblock(address: String): Boolean = io(false) {
        BlockedNumberContract.unblock(context, address) > 0
    }

    override suspend fun list(): List<String> = io(emptyList()) {
        val out = ArrayList<String>()
        context.contentResolver.query(
            BlockedNumberContract.BlockedNumbers.CONTENT_URI,
            arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
            null,
            null,
            null,
        )?.use { c ->
            val i = c.getColumnIndex(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER)
            while (i >= 0 && c.moveToNext()) {
                c.getString(i)?.let { out += it }
            }
        }
        out
    }

    /** True when this user/app may read and write the list right now. */
    fun canBlock(): Boolean = try {
        BlockedNumberContract.canCurrentUserBlockNumbers(context)
    } catch (e: Exception) {
        false
    }

    private suspend fun <T> io(fallback: T, block: () -> T): T = withContext(Dispatchers.IO) {
        if (!canBlock()) return@withContext fallback
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "blocked numbers unavailable: ${e.javaClass.simpleName}")
            fallback
        }
    }
}
