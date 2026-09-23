package app.dak.di

import javax.inject.Qualifier

/** DataStore holding user settings (registry rows). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStore

/** DataStore holding non-setting app state (onboarding progress, prompts). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppStateDataStore

/** Process-wide coroutine scope (SupervisorJob + Default dispatcher) for fire-and-forget work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
