package app.dak.automation

import app.dak.index.maintenance.MaintenanceTask
import javax.inject.Inject

/**
 * Contributes [DailyHousekeeping] to the single daily `dak-maintenance` job, so expired forwarding rules are
 * disabled, the forwarding notification refreshed and birthday wishes re-scheduled even on days without any
 * incoming message. [DailyHousekeeping.runIfDue] makes it a no-op when an SMS already triggered today's pass.
 */
class AutomationMaintenanceTask @Inject constructor(private val housekeeping: DailyHousekeeping) : MaintenanceTask {
    override val name: String get() = "automations-housekeeping"
    override val order: Int get() = 110

    override suspend fun run() {
        housekeeping.runIfDue()
    }
}
