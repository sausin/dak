package app.dak.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.security.LockMethodChoice

/**
 * Onboarding's last-step recommendation to turn on the app lock (off by default). Uses the phone's screen lock when
 * there is one (verified with the system prompt), otherwise sets up an app PIN. Everything else is in Settings.
 */
@Composable
fun AppLockOnboardingCard(modifier: Modifier = Modifier) {
    val manager = rememberAppLockManager()
    val lock by manager.state.collectAsState()
    val device = rememberDeviceAuthenticator()
    var setupPin by remember { mutableStateOf(false) }
    val verifyTitle = stringResource(R.string.lock_verify_device_title)
    val enabled = lock.config.enabled

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    if (enabled) Icons.Outlined.CheckCircle else Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp).size(24.dp),
                )
                Column {
                    Text(stringResource(R.string.lock_onboarding_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(if (enabled) R.string.lock_onboarding_done else R.string.lock_onboarding_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!enabled) {
                OutlinedButton(
                    onClick = {
                        if (manager.deviceStatus().deviceSecure) {
                            device.deviceLock(verifyTitle, null) { outcome ->
                                if (outcome == PromptOutcome.SUCCESS) manager.setMethod(LockMethodChoice.DEVICE)
                            }
                        } else {
                            setupPin = true
                        }
                    },
                ) { Text(stringResource(R.string.lock_onboarding_button)) }
            }
        }
    }

    if (setupPin) {
        PinSetupDialog(
            onDismiss = { setupPin = false },
            onDone = {
                setupPin = false
                manager.setMethod(LockMethodChoice.APP_PIN)
            },
        )
    }
}
