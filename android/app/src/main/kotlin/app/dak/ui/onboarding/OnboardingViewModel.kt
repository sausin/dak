package app.dak.ui.onboarding

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.di.AndroidContactLookup
import app.dak.di.IndexControl
import app.dak.settings.AppStateStore
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.telephony.SimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Onboarding order is fixed by policy: explain limits → role dialog → runtime permissions → indexing → reliability. */
enum class OnboardingStep { WELCOME, DEFAULT_APP, PERMISSIONS, INDEXING, RELIABILITY }

/** When the full-history index backfill runs (recent messages are always indexed immediately). */
enum class IndexChoice(val settingValue: String) { NOW("now"), PLUGGED_IN("plugged"), TONIGHT("tonight") }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val isDefaultSmsApp: Boolean = false,
    val roleDeclined: Boolean = false,
    val notificationsGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val phoneGranted: Boolean = false,
    val indexChoice: IndexChoice = IndexChoice.TONIGHT,
    val batteryOptimizationIgnored: Boolean = false,
    val backgroundRestricted: Boolean = false,
    val oem: OemGuidance? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val appState: AppStateStore,
    private val settings: SettingsStore,
    private val contacts: AndroidContactLookup,
    private val sims: SimRepository,
    private val indexScheduler: IndexControl,
) : ViewModel() {

    private val _state = MutableStateFlow(
        OnboardingUiState(
            step = savedStateHandle.get<String>(KEY_STEP)?.let { runCatching { OnboardingStep.valueOf(it) }.getOrNull() }
                ?: OnboardingStep.WELCOME,
            indexChoice = IndexChoice.entries.firstOrNull { it.settingValue == settings.get(DakSettings.indexSchedule) }
                ?: IndexChoice.TONIGHT,
            oem = OemGuidance.forThisDevice(),
        ),
    )
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads role, permission and battery state (call on resume and after every system dialog). */
    fun refresh() {
        _state.update {
            it.copy(
                isDefaultSmsApp = SmsRole.isDefault(context),
                notificationsGranted = RuntimePermissions.notificationsGranted(context),
                contactsGranted = RuntimePermissions.allGranted(context, RuntimePermissions.contacts),
                phoneGranted = RuntimePermissions.allGranted(context, RuntimePermissions.phone),
                batteryOptimizationIgnored = BatteryOptimization.isIgnoring(context),
                backgroundRestricted = BatteryOptimization.isBackgroundRestricted(context),
            )
        }
    }

    fun onRoleResult() {
        refresh()
        val isDefault = _state.value.isDefaultSmsApp
        _state.update { it.copy(roleDeclined = !isDefault) }
        if (isDefault) {
            // Recent messages are indexed straight away so the app is usable within seconds.
            indexScheduler.startInitialSync()
            goTo(OnboardingStep.PERMISSIONS)
        }
    }

    fun onPermissionsResult() {
        refresh()
        contacts.invalidate()
        sims.refresh()
        goTo(OnboardingStep.INDEXING)
    }

    fun chooseIndex(choice: IndexChoice) {
        _state.update { it.copy(indexChoice = choice) }
    }

    fun confirmIndex() {
        val choice = _state.value.indexChoice
        settings.set(DakSettings.indexSchedule, choice.settingValue)
        indexScheduler.scheduleBackfill(choice.settingValue)
        goTo(OnboardingStep.RELIABILITY)
    }

    fun next() {
        val current = _state.value.step
        val next = when (current) {
            OnboardingStep.WELCOME -> if (_state.value.isDefaultSmsApp) OnboardingStep.PERMISSIONS else OnboardingStep.DEFAULT_APP
            OnboardingStep.DEFAULT_APP -> OnboardingStep.PERMISSIONS
            OnboardingStep.PERMISSIONS -> OnboardingStep.INDEXING
            OnboardingStep.INDEXING -> OnboardingStep.RELIABILITY
            OnboardingStep.RELIABILITY -> OnboardingStep.RELIABILITY
        }
        goTo(next)
    }

    /** Returns false when already at the first step (let the system handle back). */
    fun back(): Boolean {
        val previous = when (_state.value.step) {
            OnboardingStep.WELCOME -> return false
            OnboardingStep.DEFAULT_APP -> OnboardingStep.WELCOME
            // Never step back past the role dialog once it is granted.
            OnboardingStep.PERMISSIONS -> if (_state.value.isDefaultSmsApp) return false else OnboardingStep.DEFAULT_APP
            OnboardingStep.INDEXING -> OnboardingStep.PERMISSIONS
            OnboardingStep.RELIABILITY -> OnboardingStep.INDEXING
        }
        goTo(previous)
        return true
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            appState.setOnboardingCompleted(true)
            onDone()
        }
    }

    private fun goTo(step: OnboardingStep) {
        savedStateHandle[KEY_STEP] = step.name
        _state.update { it.copy(step = step) }
    }

    private companion object {
        const val KEY_STEP = "onboarding.step"
    }
}
