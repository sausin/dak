package app.dak.ui.ux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import app.dak.settings.SwipeActions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What a swipe on an inbox conversation does (see [DakSettings.swipeRight] / [DakSettings.swipeLeft]). */
enum class InboxSwipeAction {
    ARCHIVE, DELETE, MARK_READ, PIN, NONE;

    companion object {
        /** Maps a stored [SwipeActions] value; unknown values turn the swipe off rather than guess. */
        fun fromSetting(value: String): InboxSwipeAction = when (value) {
            SwipeActions.ARCHIVE -> ARCHIVE
            SwipeActions.DELETE -> DELETE
            SwipeActions.MARK_READ -> MARK_READ
            SwipeActions.PIN -> PIN
            else -> NONE
        }
    }
}

/**
 * Gesture and input preferences shared by the inbox and the conversation screen: the two inbox swipe actions,
 * the inbox "Copy code" chip, and whether the keyboard's Enter key sends.
 */
@HiltViewModel
class UxPrefsViewModel @Inject constructor(settings: SettingsStore) : ViewModel() {

    /** Physical swipe to the right (the row maps it to start/end for RTL layouts). */
    val swipeRight: StateFlow<InboxSwipeAction> = settings.observe(DakSettings.swipeRight)
        .map(InboxSwipeAction::fromSetting)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxSwipeAction.fromSetting(DakSettings.swipeRight.default))

    /** Physical swipe to the left. */
    val swipeLeft: StateFlow<InboxSwipeAction> = settings.observe(DakSettings.swipeLeft)
        .map(InboxSwipeAction::fromSetting)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxSwipeAction.fromSetting(DakSettings.swipeLeft.default))

    val inboxOtpCopy: StateFlow<Boolean> = settings.observe(DakSettings.inboxOtpCopy)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DakSettings.inboxOtpCopy.default)

    val enterToSend: StateFlow<Boolean> = settings.observe(DakSettings.enterToSend)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DakSettings.enterToSend.default)
}
