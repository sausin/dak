package app.dak.automation

import app.dak.automations.action.Archiver
import app.dak.automations.action.Binner
import app.dak.automations.undo.UndoToken
import app.dak.core.model.MessageKey
import app.dak.index.bin.DeletedBy
import app.dak.index.bin.RecycleBin
import app.dak.index.repo.ConversationRepository

/**
 * [Archiver] over the index's per-message archive flag, for one rule (so the undo snackbar can name it).
 * Created per rule execution by [AutomationRunner].
 */
class IndexArchiver(
    private val conversations: ConversationRepository,
    private val undoCenter: AutomationUndoCenter,
    private val ruleName: String,
    private val clock: () -> Long,
) : Archiver {
    override suspend fun archive(messageKey: String): UndoToken {
        val key = MessageKey.parse(messageKey) ?: error("bad message key $messageKey")
        conversations.setMessageArchived(key, true)
        val token = UndoToken(messageKey, "archive", "Archived by \"$ruleName\"", clock() + AutomationUndoCenter.UNDO_WINDOW_MILLIS)
        undoCenter.register(token, ruleName) { conversations.setMessageArchived(key, false) }
        return token
    }
}

/**
 * [Binner] over the recycle bin: the message is copied into the bin with `deletedBy = auto-rule:<rule name>`
 * before it leaves the provider, and undo restores it.
 */
class IndexBinner(
    private val bin: RecycleBin,
    private val undoCenter: AutomationUndoCenter,
    private val ruleName: String,
    private val clock: () -> Long,
) : Binner {
    override suspend fun delete(messageKey: String, deletedBy: String): UndoToken {
        val key = MessageKey.parse(messageKey) ?: error("bad message key $messageKey")
        val receipt = bin.moveToBin(listOf(key), DeletedBy.AutoRule(ruleName))
        check(receipt.failed.isEmpty()) { "could not move $messageKey to the bin" }
        val token = UndoToken(messageKey, "delete", "Moved to bin by \"$ruleName\"", clock() + AutomationUndoCenter.UNDO_WINDOW_MILLIS)
        undoCenter.register(token, ruleName) { bin.undo(receipt) }
        return token
    }
}
