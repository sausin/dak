package app.dak.index.maintenance

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the single daily [MaintenanceWorker] (device idle + battery not low). Enqueueing touches WorkManager's
 * database, so it is done once per install / spec version rather than on every process start (a process start
 * happens for nearly every incoming SMS). If idle never came for [CATCH_UP_AFTER_MILLIS] (a phone that is never
 * left alone), [ensureScheduled] adds one catch-up run that only needs battery-not-low.
 */
@Singleton
class MaintenanceScheduler @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Idempotent and cheap after the first call. Call off the main thread (reads SharedPreferences). */
    fun ensureScheduled(nowMillis: Long = System.currentTimeMillis()) {
        try {
            val wm = WorkManager.getInstance(context)
            if (prefs.getInt(KEY_SPEC, 0) != SPEC_VERSION) {
                wm.enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS, FLEX_HOURS, TimeUnit.HOURS)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiresDeviceIdle(true)
                                .setRequiresBatteryNotLow(true)
                                .build(),
                        )
                        .build(),
                )
                // Periodic jobs of earlier builds, now folded into this one.
                LEGACY_WORK.forEach { wm.cancelUniqueWork(it) }
                wm.cancelAllWorkByTag(LEGACY_OTP_TAG)
                prefs.edit().putInt(KEY_SPEC, SPEC_VERSION).putLong(KEY_LAST_RUN, nowMillis).apply()
                return
            }
            val lastRun = prefs.getLong(KEY_LAST_RUN, nowMillis)
            if (nowMillis - lastRun >= CATCH_UP_AFTER_MILLIS) {
                wm.enqueueUniqueWork(
                    CATCH_UP_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<MaintenanceWorker>()
                        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                        .build(),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "maintenance scheduling failed", e)
        }
    }

    /** Runs the maintenance tasks soon (battery not low), e.g. from a debug screen. */
    fun runSoon() {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                CATCH_UP_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MaintenanceWorker>()
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .build(),
            )
        }
    }

    internal fun markRan(nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_RUN, nowMillis).apply()
    }

    companion object {
        /** Unique periodic work name of the daily maintenance job. */
        const val WORK_NAME = "dak-maintenance"
        const val CATCH_UP_WORK_NAME = "dak-maintenance-catch-up"
        private const val TAG = "DakIndex"
        private const val PREFS = "dak_maintenance"
        private const val KEY_SPEC = "spec"
        private const val KEY_LAST_RUN = "lastRun"
        private const val SPEC_VERSION = 1
        private const val FLEX_HOURS = 6L
        private const val CATCH_UP_AFTER_MILLIS = 3L * 24 * 60 * 60_000L
        private val LEGACY_WORK = listOf("dak-index-reconcile", "dak-bin-purge")
        private const val LEGACY_OTP_TAG = "dak-otp-delete"
    }
}
