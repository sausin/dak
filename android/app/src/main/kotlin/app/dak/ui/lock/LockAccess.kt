package app.dak.ui.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.fragment.app.FragmentActivity
import app.dak.security.AppLockManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Hilt entry point so composables outside a ViewModel can reach the singleton [AppLockManager]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppLockEntryPoint {
    fun appLockManager(): AppLockManager
}

/** The app-wide [AppLockManager]. */
@Composable
fun rememberAppLockManager(): AppLockManager {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, AppLockEntryPoint::class.java).appLockManager()
    }
}

/** Keeps `FLAG_SECURE` on the activity window while this composable is shown (PIN pads, PIN setup). */
@Composable
fun SecureWhileShown() {
    val manager = rememberAppLockManager()
    DisposableEffect(manager) {
        val release = manager.holdSecureWindow()
        onDispose { release() }
    }
}

/**
 * Tapjacking guard: while shown, the hosting view (the activity's or a dialog's) drops touches that arrive while
 * another app's window obscures it, so an overlay cannot trick the user into tapping the lock screen or PIN pad.
 */
@Composable
fun ObscuredTouchGuard() {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.filterTouchesWhenObscured
        view.filterTouchesWhenObscured = true
        onDispose { view.filterTouchesWhenObscured = previous }
    }
}

/** The hosting [FragmentActivity] (androidx.biometric's prompt needs one), unwrapping context wrappers. */
internal fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current != null) {
        if (current is FragmentActivity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}
