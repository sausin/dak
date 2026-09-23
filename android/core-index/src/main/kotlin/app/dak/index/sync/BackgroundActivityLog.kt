package app.dak.index.sync

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug-only counters of background activity per day (process starts, jobs, reconciles), for the self-test
 * screen: a quick way to see on a real device whether Dak wakes up more than expected. In non-debuggable builds
 * every call is a no-op (no disk writes). Keeps the last [KEEP_DAYS] days.
 */
@Singleton
class BackgroundActivityLog @Inject constructor(@ApplicationContext private val context: Context) {

    /** Counts for one day, by source. */
    data class Day(val date: String, val counts: Map<String, Int>)

    /** True in debuggable builds only. */
    val enabled: Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Counts one event of [source] today. Cheap; call from background threads. */
    fun record(source: String, today: LocalDate = LocalDate.now()) {
        if (!enabled) return
        synchronized(this) {
            val name = "$today|$source"
            val editor = prefs.edit().putInt(name, prefs.getInt(name, 0) + 1)
            val oldest = today.minusDays(KEEP_DAYS - 1L).toString()
            prefs.all.keys.filter { it.substringBefore('|') < oldest }.forEach { editor.remove(it) }
            editor.apply()
        }
    }

    /** The recorded days, newest first (empty when disabled). */
    fun days(): List<Day> {
        if (!enabled) return emptyList()
        val all = synchronized(this) { prefs.all.toMap() }
        return all.entries
            .mapNotNull { (k, v) -> (v as? Int)?.let { Triple(k.substringBefore('|'), k.substringAfter('|'), it) } }
            .groupBy { it.first }
            .map { (date, rows) -> Day(date, rows.associate { it.second to it.third }.toSortedMap()) }
            .sortedByDescending { it.date }
    }

    companion object {
        const val PROCESS_START = "process-start"
        const val INCOMING = "incoming-indexed"
        const val RECONCILE = "reconcile-incremental"
        const val OTP_SWEEP = "otp-sweep-job"
        const val MAINTENANCE = "maintenance-job"
        const val BACKFILL = "backfill-job"
        private const val PREFS = "dak_background_activity"
        private const val KEEP_DAYS = 7
    }
}
