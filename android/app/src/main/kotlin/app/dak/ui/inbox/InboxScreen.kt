package app.dak.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.dak.navigation.DakNavigator
import app.dak.navigation.Routes
import app.dak.ui.common.DakTopAppBar

/** Placeholder written by the shell agent; the screens agent replaces this body (signature is pinned). */
@Composable
fun InboxScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier, topBar = { DakTopAppBar(title = "Dak") }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "Search" to Routes.search(),
                "New message" to Routes.compose(),
                "Passbook" to Routes.PASSBOOK,
                "Recycle bin" to Routes.BIN,
                "Automations" to Routes.AUTOMATIONS,
                "Backup" to Routes.BACKUP,
                "Blocked" to Routes.BLOCKED,
                "Notification self-test" to Routes.SELF_TEST,
                "Settings" to Routes.settings(),
            ).forEach { (label, route) ->
                OutlinedButton(onClick = { navigator.navigate(route) }) { Text(label) }
            }
        }
    }
}
