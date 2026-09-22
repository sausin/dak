package app.dak.notifications

import app.dak.classify.ClassifierPipeline
import app.dak.classify.NaiveBayesModel
import app.dak.classify.TemplateBundle
import app.dak.core.model.Classification
import app.dak.core.model.Message
import app.dak.di.AndroidContactLookup
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
class NotificationClassifier @Inject constructor(private val contacts: AndroidContactLookup) {

    private val pipeline: ClassifierPipeline by lazy {
        ClassifierPipeline(
            templates = TemplateBundle.loadDefault(),
            model = NaiveBayesModel.loadDefault(),
            contactLookup = { address -> contacts.isContact(address) },
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
