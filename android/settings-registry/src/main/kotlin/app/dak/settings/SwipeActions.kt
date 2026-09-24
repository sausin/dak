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

    /** Choice options in the order the settings dialog lists them. Shared by both swipe rows: one label resource each. */
    val options: List<ChoiceOption> = listOf(
        ChoiceOption(ARCHIVE, "Archive", labelKey = "swipe_action_archive"),
        ChoiceOption(DELETE, "Delete (move to bin)", labelKey = "swipe_action_delete"),
        ChoiceOption(MARK_READ, "Mark as read", labelKey = "swipe_action_mark_read"),
        ChoiceOption(PIN, "Pin or unpin", labelKey = "swipe_action_pin"),
        ChoiceOption(NONE, "Nothing", labelKey = "swipe_action_none"),
    )
}
