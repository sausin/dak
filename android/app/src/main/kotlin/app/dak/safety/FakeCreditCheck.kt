package app.dak.safety

import app.dak.classify.scam.FakeCreditDetector
import app.dak.classify.scam.ScamLabels
import app.dak.classify.scam.ScamVerdict
import app.dak.core.model.Message
import app.dak.core.model.MessageBox
import app.dak.di.AndroidContactLookup
import app.dak.index.enrich.ClassifierAssets
import app.dak.settings.DakSettings
import app.dak.settings.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fake-credit scam check for paths that run before the index (the notification), and the "Warn about fake credit
 * alerts" switch for every warning surface (notification, bubble banner, inbox chip). On-device rules only
 * (`app.dak.classify.scam.FakeCreditDetector`); see docs/security/fake-credit-scams.md.
 *
 * Known accounts and message history are not available here, so follow-ups that only the index can link to an
 * earlier fake credit are flagged later, in the conversation.
 */
@Singleton
class FakeCreditCheck @Inject constructor(
    private val contacts: AndroidContactLookup,
    private val settings: SettingsStore,
) {
    private val detector: FakeCreditDetector by lazy { FakeCreditDetector(ClassifierAssets.defaultTemplates) }

    /** True when the user wants fake-credit warnings shown (default on). */
    fun warningsEnabled(): Boolean = settings.get(DakSettings.fakeCreditWarnings)

    /** Verdict for an incoming [message]; [ScamVerdict.None] when warnings are off or on any error. */
    fun verdictFor(message: Message): ScamVerdict {
        if (message.box != MessageBox.INBOX || !warningsEnabled()) return ScamVerdict.None
        return try {
            if (!detector.isCandidate(message.address, message.body)) {
                ScamVerdict.None
            } else {
                detector.evaluate(
                    address = message.address,
                    body = message.body,
                    isSavedContact = contacts.isContact(message.address),
                    dateMillis = message.dateMillis,
                )
            }
        } catch (e: RuntimeException) {
            ScamVerdict.None
        }
    }

    /** The warning to show for an indexed message's [labels], or null (none, dismissed, or warnings off). */
    fun verdictFromLabels(labels: Set<String>): ScamVerdict? =
        if (warningsEnabled()) ScamLabels.fromLabels(labels) else null
}
