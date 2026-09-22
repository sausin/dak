package app.dak.di

import android.content.Context
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Settings persistence: DataStore files, the registry adapter and the app-wide [SettingsStore]. */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
