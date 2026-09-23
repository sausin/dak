package app.dak.automations.undo

/**
 * Describes an undoable archive/delete for the few-seconds "undo" affordance (see the recycle-bin
 * design). Purely descriptive; `:app` is responsible for actually reversing the effect it names.
 */
public data class UndoToken(
    val messageKey: String,
    /** "archive" or "delete". */
    val actionType: String,
    val description: String,
    val expiresAtMillis: Long,
)
