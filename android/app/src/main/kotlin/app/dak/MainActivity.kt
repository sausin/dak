package app.dak

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import app.dak.navigation.DakNavHost
import app.dak.navigation.IntentRoutes
import app.dak.navigation.PendingShare
import app.dak.navigation.Routes
import app.dak.security.AppLockManager
import app.dak.settings.AppSettingsStore
import app.dak.settings.AppStateStore
import app.dak.ui.lock.AppLockOverlay
import app.dak.ui.theme.DakTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

/**
 * The single activity. Hosts the NavHost inside [DakTheme], applies theme changes live (it handles `uiMode`
 * configuration changes itself, so a system dark-mode switch recomposes instead of recreating), and turns
 * launcher / notification / SENDTO / SEND intents into routes.
 *
 * App lock: a [FragmentActivity] because androidx.biometric's prompt needs one. While [AppLockManager] reports the
 * app locked, [AppLockOverlay] covers the NavHost (whose semantics are cleared so accessibility services cannot read
 * what is underneath), intent routes wait until the user unlocks, and on a cold start the NavHost is not composed at
 * all until the first unlock. `FLAG_SECURE` follows [app.dak.security.AppLockState.secureWindow].
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settings: AppSettingsStore
    @Inject lateinit var appState: AppStateStore
    @Inject lateinit var pendingShare: PendingShare
    @Inject lateinit var appLock: AppLockManager

    /** Routes requested by intents, consumed by the NavHost once it exists. */
    private val routeRequests = Channel<String>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appLock.start()
        // Before the first frame, so a locked or private app never shows up in Recents or a screenshot.
        applySecureWindow(appLock.state.value.secureWindow)
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
                // Not lifecycle-bound on purpose: the lock (and FLAG_SECURE) must apply while in the background too.
                val lock by appLock.state.collectAsState()
                LaunchedEffect(lock.secureWindow) { applySecureWindow(lock.secureWindow) }
                // Cold start while locked: compose nothing of the app (no screen, no ViewModel) until the first unlock.
                var contentAllowed by remember { mutableStateOf(!lock.locked) }
                LaunchedEffect(lock.locked) { if (!lock.locked) contentAllowed = true }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val startDestination = start
                    if (startDestination != null && contentAllowed) {
                        Box(Modifier.fillMaxSize().then(if (lock.locked) Modifier.clearAndSetSemantics {} else Modifier)) {
                            val navController = rememberNavController()
                            DakNavHost(navController = navController, startDestination = startDestination)
                            LaunchedEffect(navController) {
                                routeRequests.receiveAsFlow().collectLatest { route ->
                                    // Deep links (e.g. a notification tap) wait for the unlock, then land where asked.
                                    appLock.awaitUnlocked()
                                    // Intent routes only apply once onboarding is done; before that there is nothing to open.
                                    if (navController.currentDestination?.route != Routes.ONBOARDING) {
                                        // The activity is exported: an unknown route from another app must not crash us.
                                        runCatching { navController.navigate(route) { launchSingleTop = true } }
                                    }
                                }
                            }
                        }
                    }
                    if (lock.locked) AppLockOverlay(lock)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun applySecureWindow(secure: Boolean) {
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
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
