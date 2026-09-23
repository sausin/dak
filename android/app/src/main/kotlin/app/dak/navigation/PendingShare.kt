package app.dak.navigation

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media shared into Dak from another app (ACTION_SEND with EXTRA_STREAM). The COMPOSE route only carries text,
 * so MainActivity parks shared URIs here; the composer reads them once with [consume].
 */
@Singleton
class PendingShare @Inject constructor() {
    private val state = MutableStateFlow<List<Uri>>(emptyList())

    /** URIs waiting to be attached. */
    val uris: StateFlow<List<Uri>> = state

    fun offer(uris: List<Uri>) {
        if (uris.isNotEmpty()) state.value = uris
    }

    /** Returns and clears the pending URIs. */
    fun consume(): List<Uri> {
        val current = state.value
        state.value = emptyList()
        return current
    }
}
