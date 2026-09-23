package app.dak.ui.fraud

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.dak.navigation.DakNavigator
import app.dak.ui.common.PlaceholderScreen

@Composable
fun FraudHelpScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Report fraud", navigator = navigator, modifier = modifier)
}
