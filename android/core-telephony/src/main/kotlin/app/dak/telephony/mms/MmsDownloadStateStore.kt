package app.dak.telephony.mms

import android.content.Context
import app.dak.telephony.MmsDownloadState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Persistent MMS download state per notification-ind row id (SharedPreferences, so a failure reason and attempt
 * count survive process death and reboots), plus the id of the retrieved message that replaced it.
 */
@Singleton
class MmsDownloadStateStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("dak_mms_downloads", Context.MODE_PRIVATE)
    private val states = MutableStateFlow(load())

    fun get(id: Long): MmsDownloadState? = states.value[id]

    fun observe(id: Long): Flow<MmsDownloadState?> = states.map { it[id] }.distinctUntilChanged()

    @Synchronized
    fun set(id: Long, state: MmsDownloadState) {
        prefs.edit().putString(STATE_PREFIX + id, MmsDownloadStateCodec.encode(state)).commit()
        states.update { it + (id to state) }
    }

    /** Marks [id] done and remembers the provider id of the retrieved message that replaced it. */
    @Synchronized
    fun setDone(id: Long, replacementId: Long?) {
        val edit = prefs.edit().putString(STATE_PREFIX + id, MmsDownloadStateCodec.encode(MmsDownloadState.Done))
        if (replacementId != null) edit.putLong(REPLACEMENT_PREFIX + id, replacementId)
        edit.commit()
        states.update { it + (id to MmsDownloadState.Done) }
    }

    fun replacement(id: Long): Long? = prefs.getLong(REPLACEMENT_PREFIX + id, -1L).takeIf { it >= 0 }

    private fun load(): Map<Long, MmsDownloadState> {
        val out = HashMap<Long, MmsDownloadState>()
        for ((key, value) in prefs.all) {
            if (!key.startsWith(STATE_PREFIX)) continue
            val id = key.removePrefix(STATE_PREFIX).toLongOrNull() ?: continue
            MmsDownloadStateCodec.decode(value as? String)?.let { out[id] = it }
        }
        return out
    }

    private companion object {
        const val STATE_PREFIX = "s:"
        const val REPLACEMENT_PREFIX = "r:"
    }
}
