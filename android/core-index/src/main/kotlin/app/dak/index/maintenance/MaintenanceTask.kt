package app.dak.index.maintenance

/**
 * A unit of low-priority housekeeping run by the single daily maintenance job ([MaintenanceWorker], unique
 * periodic work [MaintenanceScheduler.WORK_NAME], constraints: device idle + battery not low). Use this instead of
 * enqueueing your own periodic worker: every extra periodic job is another wakeup Android counts against the app.
 *
 * Contribute one from any Hilt module installed in `SingletonComponent`:
 * ```kotlin
 * @Binds @IntoSet abstract fun birthdays(impl: BirthdayRescheduleTask): MaintenanceTask
 * ```
 * Tasks run sequentially on a background dispatcher, in ascending [order], each isolated (a failure is logged and
 * the next task still runs). Keep each one short (well under a minute) and idempotent: the job can be stopped when
 * the device leaves idle, and a task may be skipped on such a day. Do not rely on an exact time of day; tasks
 * that need precise timing (a birthday message at 09:00) should only *re-arm* their own alarm here.
 */
interface MaintenanceTask {
    /** Short stable name for logs and the debug activity log. */
    val name: String

    /** Lower runs first. Built-in tasks use 0-99; features should use 100+. */
    val order: Int get() = 100

    /** Does the work. Called off the main thread; must be safe to call again after a partial run. */
    suspend fun run()
}
