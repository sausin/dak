package app.dak.notifications

import app.dak.classify.ClassifierPipeline
import app.dak.classify.SenderRegion
import app.dak.core.model.Classification
import app.dak.core.model.Message
import app.dak.di.AndroidContactLookup
import app.dak.index.enrich.ClassifierAssets
import app.dak.telephony.region.RegionProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies an incoming message for the notification path by calling :classify's pipeline directly, so a
 * notification never waits for the index. On-device stages only (no cloud stage here: the notification must not
 * depend on the network, and cloud classification is opt-in and handled by the index).
 */
@Singleton
class NotificationClassifier @Inject constructor(
    private val contacts: AndroidContactLookup,
    private val regions: RegionProvider,
) {

    private val pipeline: ClassifierPipeline by lazy {
        // Shared with the index enricher: the bundled JSON is parsed once per process.
        ClassifierPipeline(
            templates = ClassifierAssets.defaultTemplates,
            model = ClassifierAssets.model,
            contactLookup = { address -> contacts.isContact(address) },
            // Sender conventions (India's DLT headers, short codes elsewhere) follow the SIM the message came in on.
            regionFor = { subId -> SenderRegion.of(regions.forSubId(subId).countryIso) },
        )
    }

    // The pipeline keeps an unsynchronised regex cache; serialise calls (each takes well under a millisecond).
    private val lock = Mutex()

    suspend fun classify(message: Message): Classification = lock.withLock {
        try {
            pipeline.classify(message.address, message.body, message.subId)
        } catch (e: RuntimeException) {
            Classification.Unclassified
        }
    }
}
