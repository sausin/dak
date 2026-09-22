package app.dak.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.dak.navigation.DakNavigator

/** Temporary screen body used by stubs until the real screen lands. */
@Composable
fun PlaceholderScreen(title: String, navigator: DakNavigator, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = { DakTopAppBar(title = title, onBack = { navigator.back() }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            EmptyState(icon = Icons.Outlined.Construction, title = title, body = null)
        }
    }
}
