package app.dak

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.dak.notifications.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Supplies WorkManager with Hilt's worker factory (the default initializer is removed in
 * the manifest) and makes sure notification channels exist before any message can arrive.
 */
@HiltAndroidApp
class DakApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notificationChannels: NotificationChannels

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        notificationChannels.ensureCreated()
    }
}
