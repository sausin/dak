package app.dak.ui.privacy

import android.content.Context
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dak.security.AppLockManager
import app.dak.settings.AppSettingsStore
import app.dak.ui.lock.AppLockOverlay
import app.dak.ui.theme.DakTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Settings → Privacy, as its own (non-exported) activity so it is reachable from Settings, onboarding and every
 * disclosure without touching the main navigation graph: the privacy policy (bundled, works offline), the data that
 * can leave the phone with consent and withdrawal, the consent history, "Export my Dak data" and "Delete my Dak data".
 *
 * App lock applies here exactly as in `MainActivity`: while locked, [AppLockOverlay] covers the content (whose
 * semantics are cleared) and the window is secure.
 */
@AndroidEntryPoint
class PrivacyActivity : FragmentActivity() {

    @Inject lateinit var settings: AppSettingsStore
    @Inject lateinit var appLock: AppLockManager

    /** Which screen to open first. */
    enum class Start { HUB, POLICY, SHARING, EXPORT, DELETE }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appLock.start()
        applySecureWindow(appLock.state.value.secureWindow)
        val start = intent?.getStringExtra(EXTRA_START)?.let { name -> Start.entries.firstOrNull { it.name == name } } ?: Start.HUB
        val initialAppearance = settings.appearanceNow()
        setContent {
            val appearance by settings.appearance.collectAsStateWithLifecycle(initialValue = initialAppearance)
            DakTheme(prefs = appearance) {
                val dark = DakTheme.state.isDark
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    )
                    onDispose {}
                }
                val lock by appLock.state.collectAsState()
                LaunchedEffect(lock.secureWindow) { applySecureWindow(lock.secureWindow) }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Box(Modifier.fillMaxSize().then(if (lock.locked) Modifier.clearAndSetSemantics {} else Modifier)) {
                        PrivacyApp(start = start, onClose = { finish() })
                    }
                    if (lock.locked) AppLockOverlay(lock)
                }
            }
        }
    }

    private fun applySecureWindow(secure: Boolean) {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    companion object {
        private const val EXTRA_START = "app.dak.privacy.START"

        fun intent(context: Context, start: Start = Start.HUB): Intent =
            Intent(context, PrivacyActivity::class.java)
                .putExtra(EXTRA_START, start.name)
                .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
}
