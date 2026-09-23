package app.dak.ui.bin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.core.model.SimInfo
import app.dak.index.BinItem
import app.dak.index.bin.RecycleBin
import app.dak.security.AppLockManager
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.telephony.SimRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Results of bin actions, for the snackbar. */
sealed interface BinEvent {
    data object Restored : BinEvent
    data object RestoreFailed : BinEvent
    data class Emptied(val count: Int) : BinEvent
}

/**
 * Recycle bin: every soft-deleted message with who deleted it (manual, a rule, OTP auto-delete, consumed OTP) and
 * when it will be purged; restore puts it back into its original thread. Optionally behind a biometric lock
 * (Settings → Backup and data → Lock the recycle bin); the unlock lasts for this screen's lifetime.
 */
@HiltViewModel
class BinViewModel @Inject constructor(
    private val bin: RecycleBin,
    settings: SettingsStore,
    sims: SimRepository,
    appLock: AppLockManager,
) : ViewModel() {

    val lockEnabled: Boolean = settings.get(DakSettings.binBiometricLock)

    // In memory only: the lock returns after process death, never restored from saved state.
    // Already authenticated in this session (app unlock or another sensitive-screen check): no second prompt.
    private val unlockedState = MutableStateFlow(!lockEnabled || appLock.isSensitiveUnlocked())
    val unlocked: StateFlow<Boolean> = unlockedState

    val items: StateFlow<List<BinItem>?> = bin.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sims: StateFlow<List<SimInfo>> = sims.sims

    private val eventChannel = Channel<BinEvent>(Channel.BUFFERED)
    val events: Flow<BinEvent> = eventChannel.receiveAsFlow()

    fun onUnlocked() {
        unlockedState.value = true
    }

    fun restore(item: BinItem) {
        viewModelScope.launch {
            val key = runCatching { bin.restore(item.id) }.getOrNull()
            eventChannel.trySend(if (key != null) BinEvent.Restored else BinEvent.RestoreFailed)
        }
    }

    fun deleteForever(item: BinItem) {
        viewModelScope.launch { bin.deleteForever(item.id) }
    }

    fun empty() {
        viewModelScope.launch { eventChannel.trySend(BinEvent.Emptied(bin.empty())) }
    }
}
