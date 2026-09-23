package app.dak.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.R
import app.dak.index.scam.ScamRepository
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Conversations with a message flagged as a possible fake credit alert (empty when warnings are turned off). */
@HiltViewModel
class ScamFlagsViewModel @Inject constructor(
    settings: SettingsStore,
    scams: ScamRepository,
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val flagged: StateFlow<Set<String>> = settings.observe(DakSettings.fakeCreditWarnings)
        .flatMapLatest { enabled -> if (enabled) scams.flaggedConversations() else flowOf(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
}

/** Red "Possible scam" chip on an inbox row whose conversation holds a flagged message. */
@Composable
fun ScamWarningChip(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(12.dp))
            Text(stringResource(R.string.scam_inbox_chip), style = MaterialTheme.typography.labelSmall)
        }
    }
}
