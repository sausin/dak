package app.dak.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.dak.settings.AppSettingsStore
import app.dak.settings.DataStorePersistenceAdapter
import app.dak.settings.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Settings persistence: DataStore files, the registry adapter and the app-wide [SettingsStore]. */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    /**
     * Fire-and-forget work here is triggered by incoming messages (notifications, automations, indexing): an escaping
     * exception is logged instead of crashing the process, so a crafted SMS cannot crash-loop the default SMS app.
     * Out-of-memory still crashes.
     */
    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            if (e is OutOfMemoryError) throw e
            Log.e("DakApp", "uncaught exception in application scope", e)
        },
    )

    @Provides @Singleton @SettingsDataStore
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile("settings") },
        )

    @Provides @Singleton @AppStateDataStore
    fun appStateDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile("app_state") },
        )

    @Provides @Singleton
    fun persistenceAdapter(
        @SettingsDataStore dataStore: DataStore<Preferences>,
        @ApplicationScope scope: CoroutineScope,
    ): DataStorePersistenceAdapter = DataStorePersistenceAdapter(dataStore, scope)

    @Provides @Singleton
    fun appSettingsStore(adapter: DataStorePersistenceAdapter): AppSettingsStore = AppSettingsStore(adapter)

    @Provides @Singleton
    fun settingsStore(store: AppSettingsStore): SettingsStore = store
}
