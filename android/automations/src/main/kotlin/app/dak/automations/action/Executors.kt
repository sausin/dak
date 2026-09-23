package app.dak.automations.action

import app.dak.automations.undo.UndoToken

/**
 * Android-side effects, implemented by `:app`. This module only decides *that* one of these should
 * happen (see [ActionRegistry]); it never touches `Telephony`, `NotificationManager`, etc. itself, so
 * it stays pure-JVM and unit-testable.
 */
public interface SmsForwarder {
    /** @return true if the SMS was handed to the platform for sending. */
    public suspend fun forward(to: String, subId: Int?, text: String): Boolean
}

public interface Notifier {
    public suspend fun notify(title: String?, text: String?): Boolean
}

public interface Labeler {
    public suspend fun label(messageKey: String, label: String): Boolean
}

public interface Archiver {
    public suspend fun archive(messageKey: String): UndoToken
}

/** Soft-deletes a message to the recycle bin. */
public interface Binner {
    public suspend fun delete(messageKey: String, deletedBy: String): UndoToken
}

public interface IntentLauncher {
    public suspend fun launch(uri: String): Boolean
}

public interface ReplyScheduler {
    /** Schedules (does not send inline) a reply, e.g. via `AlarmManager`/`WorkManager`. */
    public suspend fun scheduleReply(to: String, subId: Int?, text: String, atMillis: Long): Boolean
}
