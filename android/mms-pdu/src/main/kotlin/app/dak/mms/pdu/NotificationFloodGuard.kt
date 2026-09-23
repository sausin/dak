package app.dak.mms.pdu

/**
 * Rate limits for incoming m-notification-ind WAP pushes. Anyone who knows the number can send them, each distinct
 * content location becomes an inbox row and (with auto-download on) a fetch over the carrier's MMS APN, so a flood
 * of forged notifications would otherwise fill the inbox, keep the radio busy and turn every push into a "the device
 * fetched my URL" beacon.
 *
 * [decide] is called once per new (not duplicate) notification:
 * - [Decision.AUTO_DOWNLOAD] while the sender and the device are under their hourly budgets;
 * - [Decision.MANUAL] once a budget is used up: the notification is stored and shown with "tap to download";
 * - [Decision.DROP] once [maxStoredPerWindow] notifications arrived within the window: nothing is stored.
 *
 * In memory only: a flood keeps the process alive, and a restart simply starts a new window. Thread-safe.
 */
class NotificationFloodGuard(
    private val maxAutoPerSender: Int = DEFAULT_MAX_AUTO_PER_SENDER,
    private val maxAutoPerWindow: Int = DEFAULT_MAX_AUTO_PER_WINDOW,
    private val maxStoredPerWindow: Int = DEFAULT_MAX_STORED_PER_WINDOW,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    enum class Decision { AUTO_DOWNLOAD, MANUAL, DROP }

    private class Event(val atMillis: Long, val sender: String, val auto: Boolean)

    private val events = ArrayDeque<Event>()

    init {
        require(maxAutoPerSender > 0 && maxAutoPerWindow > 0 && maxStoredPerWindow > 0 && windowMillis > 0)
    }

    /** Decides what to do with a new notification from [sender] (null when the push carried no From). */
    @Synchronized
    fun decide(sender: String?, nowMillis: Long): Decision {
        while (events.isNotEmpty() && events.first().atMillis <= nowMillis - windowMillis) events.removeFirst()
        if (events.size >= maxStoredPerWindow) return Decision.DROP
        val key = sender?.trim()?.lowercase()?.take(MmsLimits.MAX_ADDRESS_CHARS).orEmpty()
        var autoTotal = 0
        var autoFromSender = 0
        for (e in events) {
            if (!e.auto) continue
            autoTotal++
            if (e.sender == key) autoFromSender++
        }
        val auto = autoTotal < maxAutoPerWindow && autoFromSender < maxAutoPerSender
        events.addLast(Event(nowMillis, key, auto))
        return if (auto) Decision.AUTO_DOWNLOAD else Decision.MANUAL
    }

    companion object {
        const val DEFAULT_MAX_AUTO_PER_SENDER: Int = 10
        const val DEFAULT_MAX_AUTO_PER_WINDOW: Int = 30
        const val DEFAULT_MAX_STORED_PER_WINDOW: Int = 200
        const val DEFAULT_WINDOW_MILLIS: Long = 60 * 60 * 1000L
    }
}
