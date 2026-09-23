package app.dak.safety.helplines

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A number the user added themselves (e.g. "My bank's card-block number"). Always shown as unverified. */
@Serializable
data class UserHelpline(val id: String, val label: String, val number: String)

/**
 * User-entered helplines, stored on device only. Dak never ships bank numbers it has not verified, so the Report
 * fraud screen lets the user save their own bank's card-block number (from the back of the card or the bank's
 * site) and labels it as added by them.
 */
@Singleton
class UserHelplines @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(UserHelpline.serializer())
    private val state = MutableStateFlow(read())

    val all: StateFlow<List<UserHelpline>> = state.asStateFlow()

    /** Adds a helpline; blank labels/numbers or numbers with no digits are ignored. Returns true if saved. */
    fun add(label: String, number: String): Boolean {
        val cleanNumber = number.trim().filter { it.isDigit() || it == '+' || it == ' ' || it == '-' }
        if (label.isBlank() || cleanNumber.none { it.isDigit() }) return false
        write(state.value + UserHelpline(UUID.randomUUID().toString(), label.trim().take(MAX_LABEL), cleanNumber.trim()))
        return true
    }

    fun remove(id: String) = write(state.value.filterNot { it.id == id })

    private fun read(): List<UserHelpline> =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    @Synchronized
    private fun write(list: List<UserHelpline>) {
        state.value = list
        prefs.edit().putString(KEY, json.encodeToString(serializer, list)).apply()
    }

    private companion object {
        const val PREFS = "dak_user_helplines"
        const val KEY = "helplines"
        const val MAX_LABEL = 60
    }
}
