package app.dak.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import app.dak.di.AppStateDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App state that is not a user setting (never shown in Settings, never exported): onboarding progress and
 * one-time prompts.
 */
@Singleton
class AppStateStore @Inject constructor(
    @AppStateDataStore private val dataStore: DataStore<Preferences>,
) {
    private val onboardingDone = booleanPreferencesKey("onboarding.completed")
    private val reliabilityDismissedAt = longPreferencesKey("reliability.bannerDismissedAt")

    /** True once the user has finished onboarding (even if they later lose the default-SMS role). */
    val onboardingCompleted: Flow<Boolean> = dataStore.data.map { it[onboardingDone] ?: false }.distinctUntilChanged()

    suspend fun isOnboardingCompleted(): Boolean = onboardingCompleted.first()

    suspend fun setOnboardingCompleted(done: Boolean) {
        dataStore.edit { it[onboardingDone] = done }
    }

    /** When the reliability warning banner was last dismissed (it comes back after a day while still restricted). */
    val reliabilityBannerDismissedAt: Flow<Long> = dataStore.data.map { it[reliabilityDismissedAt] ?: 0L }

    suspend fun dismissReliabilityBanner(nowMillis: Long = System.currentTimeMillis()) {
        dataStore.edit { it[reliabilityDismissedAt] = nowMillis }
    }
}
