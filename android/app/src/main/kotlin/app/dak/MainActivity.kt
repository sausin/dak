package app.dak

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import app.dak.navigation.DakNavHost
import app.dak.navigation.IntentRoutes
import app.dak.navigation.PendingShare
import app.dak.navigation.Routes
import app.dak.settings.AppSettingsStore
import app.dak.settings.AppStateStore
import app.dak.ui.theme.DakTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

/**
 * The single activity. Hosts the NavHost inside [DakTheme], applies theme changes live (it handles `uiMode`
 * configuration changes itself, so a system dark-mode switch recomposes instead of recreating), and turns
 * launcher / notification / SENDTO / SEND intents into routes.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: AppSettingsStore
    @Inject lateinit var appState: AppStateStore
    @Inject lateinit var pendingShare: PendingShare

    /** Routes requested by intents, consumed by the NavHost once it exists. */
    private val routeRequests = Channel<String>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        val initialAppearance = settings.appearanceNow()
        setContent {
            val appearance by settings.appearance.collectAsStateWithLifecycle(initialValue = initialAppearance)
            DakTheme(prefs = appearance) {
                val dark = DakTheme.state.isDark
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(LightNavScrim, DarkNavScrim) { dark },
                    )
                    onDispose {}
                }
                val start by produceState<String?>(initialValue = null) {
                    value = if (appState.isOnboardingCompleted()) Routes.INBOX else Routes.ONBOARDING
                }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val startDestination = start
                    if (startDestination != null) {
                        val navController = rememberNavController()
                        DakNavHost(navController = navController, startDestination = startDestination)
                        LaunchedEffect(navController) {
                            routeRequests.receiveAsFlow().collect { route ->
                                // Intent routes only apply once onboarding is done; before that there is nothing to open.
                                if (navController.currentDestination?.route != Routes.ONBOARDING) {
                                    // The activity is exported: an unknown route from another app must not crash us.
                                    runCatching { navController.navigate(route) { launchSingleTop = true } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        pendingShare.offer(IntentRoutes.sharedStreams(this, intent))
        IntentRoutes.routeFor(this, intent)?.let { routeRequests.trySend(it) }
    }

    private companion object {
        // Same scrims the platform uses for 3-button navigation, so buttons stay legible.
        val LightNavScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DarkNavScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
