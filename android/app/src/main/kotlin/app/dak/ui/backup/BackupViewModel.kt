package app.dak.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.backup.BackupManager
import app.dak.backup.BackupOperation
import app.dak.backup.BackupScheduler
import app.dak.backup.BackupStatus
import app.dak.backup.ExportFormat
import app.dak.backup.engine.RestoreKey
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backup and export: destination folder (any document provider, including the user's own Google Drive or Dropbox
 * app), passphrase + one-time recovery code, backup now, schedule, open-format and SMS Backup & Restore exports,
 * and imports from SMS Backup & Restore, Fossify Messages and SMS Organizer.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val manager: BackupManager,
    private val settings: SettingsStore,
    private val scheduler: BackupScheduler,
) : ViewModel() {

    val status: StateFlow<BackupStatus> = manager.status
    val operation: StateFlow<BackupOperation> = manager.operation

    private val passphraseSet = MutableStateFlow(manager.hasPassphrase())
    val hasPassphrase: StateFlow<Boolean> = passphraseSet.asStateFlow()

    val schedule: StateFlow<String> = settings.observe(DakSettings.backupSchedule)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.get(DakSettings.backupSchedule))

    fun setDestination(uri: Uri) {
        viewModelScope.launch {
            manager.setDestination(uri)
            scheduler.ensureScheduled()
        }
    }

    /** Stores [passphrase] (Keystore-wrapped, on this device only) and wipes the array afterwards. */
    fun setPassphrase(passphrase: CharArray) {
        viewModelScope.launch {
            try {
                manager.setPassphrase(passphrase)
                passphraseSet.value = true
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun setSchedule(value: String) {
        settings.set(DakSettings.backupSchedule, value)
        if (value != "manual") scheduler.ensureScheduled()
    }

    fun backupNow() = manager.startBackup()

    fun restoreWithPassphrase(passphrase: CharArray) = manager.startRestore(RestoreKey.Passphrase(passphrase))

    fun restoreWithRecoveryCode(code: String) = manager.startRestore(RestoreKey.RecoveryCode(code.trim()))

    fun export(uri: Uri, format: ExportFormat) = manager.startExport(uri, format)

    fun import(uri: Uri) = manager.startImport(uri)

    fun dismissResult() = manager.clearResult()
}
