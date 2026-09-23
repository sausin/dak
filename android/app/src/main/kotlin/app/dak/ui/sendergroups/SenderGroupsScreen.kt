package app.dak.ui.sendergroups

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.dak.navigation.DakNavigator
import app.dak.ui.common.PlaceholderScreen

@Composable
fun SenderGroupsScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Sender groups", navigator = navigator, modifier = modifier)
}
