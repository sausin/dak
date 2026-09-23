package app.dak.automation

import app.dak.automations.action.ActionRegistry
import app.dak.automations.audit.AuditSink
import app.dak.automations.action.DefaultActionRegistry
import app.dak.automations.action.Labeler
import app.dak.automations.action.ReplyScheduler
import app.dak.automations.action.SmsForwarder
import app.dak.index.maintenance.MaintenanceTask
import app.dak.telephony.IncomingMessageHandler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Android implementations of :automations' executor interfaces, the automation runner hook, and the automations'
 * contribution to the daily maintenance job.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AutomationModule {

    @Binds
    abstract fun smsForwarder(impl: AndroidSmsForwarder): SmsForwarder

    @Binds
    abstract fun replyScheduler(impl: ScheduledSendScheduler): ReplyScheduler

    @Binds
    abstract fun labeler(impl: UserLabels): Labeler

    @Binds
    abstract fun auditSink(impl: IndexAuditSink): AuditSink

    @Binds @IntoSet
    abstract fun automationRunner(impl: AutomationRunner): IncomingMessageHandler

    @Binds @IntoSet
    abstract fun housekeepingTask(impl: AutomationMaintenanceTask): MaintenanceTask

    companion object {
        @Provides @Singleton
        fun actionRegistry(): ActionRegistry = DefaultActionRegistry()
    }
}
