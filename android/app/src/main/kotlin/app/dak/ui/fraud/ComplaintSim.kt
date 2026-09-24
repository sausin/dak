package app.dak.ui.fraud

import app.dak.core.model.NO_SUB_ID
import app.dak.core.model.SimInfo

/**
 * Which SIM a TRAI 1909 complaint is sent from. TRAI ties the complaint to the subscriber who received the spam, so
 * it must go out from the SIM the message arrived on. Order of preference:
 * 1. the reported message's SIM, if it is still active;
 * 2. the default SMS SIM, if active (the message's SIM was removed, or the message has no SIM);
 * 3. the only active SIM;
 * 4. [NO_SUB_ID]: let the composer pick (it asks when there is more than one SIM).
 */
object ComplaintSim {
    data class Choice(val subId: Int, val sim: SimInfo?, val isReceivingSim: Boolean)

    fun choose(messageSubId: Int, sims: List<SimInfo>, defaultSmsSubId: Int): Choice {
        val active = sims.filter { it.isActive }
        active.firstOrNull { it.subId == messageSubId && messageSubId != NO_SUB_ID }
            ?.let { return Choice(it.subId, it, isReceivingSim = true) }
        active.firstOrNull { it.subId == defaultSmsSubId && defaultSmsSubId != NO_SUB_ID }
            ?.let { return Choice(it.subId, it, isReceivingSim = false) }
        active.singleOrNull()?.let { return Choice(it.subId, it, isReceivingSim = false) }
        return Choice(NO_SUB_ID, null, isReceivingSim = false)
    }
}
