package app.dak.ui.notifications

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.dak.navigation.DakNavigator
import app.dak.ui.common.PlaceholderScreen

@Composable
fun NotificationChannelsScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Notification channels", navigator = navigator, modifier = modifier)
}
