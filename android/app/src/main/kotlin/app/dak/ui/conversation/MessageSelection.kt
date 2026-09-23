package app.dak.ui.conversation

import app.dak.index.MessageItem

/**
 * Multi-select in the thread, as plain functions over the selected message keys (`MessageKey.toString()`), so the
 * screen can keep the selection in `rememberSaveable` and it survives rotation. Keys of messages that are not
 * loaded (or no longer exist) are simply skipped when acting on the selection.
 */
internal object MessageSelection {

    /** [selection] with [key] added or removed. */
    fun toggled(selection: Set<String>, key: String): Set<String> =
        if (key in selection) selection - key else selection + key

    /** [selection] plus every key in [keys] ("Select all loaded"). */
    fun withAll(selection: Set<String>, keys: Iterable<String>): Set<String> = selection + keys

    /** The selected messages among [loaded], oldest first (the order they read in the thread). */
    fun resolve(selection: Set<String>, loaded: List<MessageItem>): List<MessageItem> =
        loaded.filter { it.key.toString() in selection }
            .distinctBy { it.key }
            .sortedWith(compareBy<MessageItem> { it.dateMillis }.thenBy { it.key.providerId })

    /** Text for copying or forwarding [ordered] (already oldest first): the non-empty bodies, a blank line apart. */
    fun copyText(ordered: List<MessageItem>): String = joinBodies(ordered.map { it.body })

    /** Trimmed, non-blank [bodies] joined with a blank line between messages. */
    fun joinBodies(bodies: List<String>): String =
        bodies.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(MESSAGE_SEPARATOR)

    private const val MESSAGE_SEPARATOR = "\n\n"
}
