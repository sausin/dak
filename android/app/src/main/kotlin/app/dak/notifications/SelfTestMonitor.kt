package app.dak.notifications

import app.dak.core.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the "send yourself a test SMS" round trip: the self-test screen [arm]s a token that is embedded in the
 * test SMS; [MessageNotifier] reports every incoming message here and posts the notification as usual; when the
 * token comes back, the state moves to [State.Received] with the measured delay.
 */
@Singleton
class SelfTestMonitor @Inject constructor() {

    sealed interface State {
        data object Idle : State
        data class Waiting(val token: String, val sentAtMillis: Long) : State
        data class Received(val token: String, val roundTripMillis: Long, val notificationPosted: Boolean) : State
    }

    private val state = MutableStateFlow<State>(State.Idle)
    val status: StateFlow<State> = state

    /** Starts waiting for [token]; returns the body to send. */
    fun arm(token: String, nowMillis: Long = System.currentTimeMillis()): String {
        state.value = State.Waiting(token, nowMillis)
        return "Dak self-test $token. If you see this as a notification, delivery works."
    }

    fun reset() {
        state.value = State.Idle
    }

    /** True when [message] is the pending test SMS. */
    fun isTestMessage(message: Message): Boolean {
        val waiting = state.value as? State.Waiting ?: return false
        return message.body.contains(waiting.token)
    }

    /** Called by the notifier after it tried to post a notification for [message]. */
    fun onNotified(message: Message, posted: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        val waiting = state.value as? State.Waiting ?: return
        if (!message.body.contains(waiting.token)) return
        state.value = State.Received(waiting.token, nowMillis - waiting.sentAtMillis, posted)
    }
}
