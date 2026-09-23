package app.dak.ui.forwarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.dak.navigation.DakNavigator
import app.dak.ui.common.PlaceholderScreen

@Composable
fun ForwardingScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Auto-forwarding", navigator = navigator, modifier = modifier)
}
