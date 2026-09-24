package app.dak

import android.app.Application
import android.content.res.Configuration as AndroidConfiguration
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.dak.automation.OutboundAutomationGuard
import app.dak.automation.ScheduledHeadsUpSettingsWatcher
import app.dak.automations.forwarding.ForwardingSpec
import app.dak.automations.safety.ForwardLoopGuard
import app.dak.di.IndexControl
import app.dak.i18n.AppLocales
import app.dak.notifications.NotificationChannels
import app.dak.settings.TelephonySettingsSync
import app.dak.telephony.sms.SmsJournalReplayWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Supplies WorkManager with Hilt's worker factory (the default initializer is removed in
 * the manifest), makes sure notification channels exist before any message can arrive, starts index sync, and starts
 * the guard that keeps automations which send messages off the phone off while no app lock is set up.
 */
@HiltAndroidApp
class DakApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notificationChannels: NotificationChannels
    @Inject lateinit var indexControl: IndexControl
    @Inject lateinit var outboundGuard: OutboundAutomationGuard.Starter
    @Inject lateinit var telephonySettingsSync: TelephonySettingsSync
    @Inject lateinit var scheduledHeadsUpSettings: ScheduledHeadsUpSettingsWatcher

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        // App language (docs/i18n.md): before anything builds text from the application context.
        AppLocales.migrateToFramework(this)
        AppLocales.applyToApplication(this)
        // Forwards made by Dak in any shipped language are recognised as forwards (never forwarded back and forth).
        ForwardLoopGuard.registerDefaultTemplates(
            AppLocales.stringInEveryLanguage(this, R.string.fw_default_template).map { ForwardingSpec.defaultTemplate(it) },
        )
        notificationChannels.ensureCreated()
        // No-op until Dak is the default SMS app; onboarding starts it right after the role is granted.
        indexControl.startInitialSync()
        // Off the main thread: follows the app lock and runs the one-time upgrade check.
        outboundGuard.start()
        // MMS read-receipt / report-allowed choices into the telephony layer's synchronous settings.
        telephonySettingsSync.start()
        // Re-plans scheduled-message heads-ups when their lead time is changed in Settings.
        scheduledHeadsUpSettings.start()
        // Incoming SMS journaled but not yet in the inbox (the process died mid-write): replay them.
        SmsJournalReplayWorker.scheduleIfPending(this)
    }

    /**
     * A language change while the process lives (system language, or the per-app language on Android 13+, which
     * reaches the application as a configuration change) renames the notification channels right away instead of
     * at the next process start. Other configuration changes cost one string comparison.
     */
    override fun onConfigurationChanged(newConfig: AndroidConfiguration) {
        super.onConfigurationChanged(newConfig)
        // Below Android 13 a system configuration change resets the application's resources to the system language.
        AppLocales.applyToApplication(this)
        notificationChannels.onLocaleMaybeChanged()
    }
}
