package app.dak.ui.birthdays

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.dak.navigation.DakNavigator
import app.dak.ui.common.PlaceholderScreen

@Composable
fun BirthdaysScreen(navigator: DakNavigator, modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Birthday wishes", navigator = navigator, modifier = modifier)
}
