package app.dak.settings

/**
 * Values of the inbox swipe settings ([DakSettings.swipeRight] / [DakSettings.swipeLeft]). The app maps each
 * value to a conversation action; [NONE] turns that swipe direction off.
 */
object SwipeActions {
    const val ARCHIVE = "archive"
    const val DELETE = "delete"
    const val MARK_READ = "markRead"
    const val PIN = "pin"
    const val NONE = "none"

    /** Choice options in the order the settings dialog lists them. */
    val options: List<ChoiceOption> = listOf(
        ChoiceOption(ARCHIVE, "Archive"),
        ChoiceOption(DELETE, "Delete (move to bin)"),
        ChoiceOption(MARK_READ, "Mark as read"),
        ChoiceOption(PIN, "Pin or unpin"),
        ChoiceOption(NONE, "Nothing"),
    )
}
