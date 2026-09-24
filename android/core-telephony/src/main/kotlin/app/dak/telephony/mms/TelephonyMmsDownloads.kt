package app.dak.telephony.mms

import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import app.dak.mms.pdu.MessageType
import app.dak.telephony.Failure
import app.dak.telephony.FailureReasons
import app.dak.telephony.MmsDownloadState
import app.dak.telephony.MmsDownloads
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * [MmsDownloads] over the persistent [MmsDownloadStateStore]. A notification row with no recorded state (e.g.
 * received before Dak became the default app) reads as `Failed("Not downloaded yet", 0)` so the UI offers a tap
 * to download; any other message reads as Done.
 */
@Singleton
class TelephonyMmsDownloads @Inject constructor(
    private val store: MmsDownloadStateStore,
    private val persister: MmsPersister,
    private val manager: MmsDownloadManager,
) : MmsDownloads {

    override fun state(key: MessageKey): Flow<MmsDownloadState> {
        if (key.kind != MessageKind.MMS) return flowOf(MmsDownloadState.Done)
        return store.observe(key.providerId)
            .map { it ?: defaultState(key.providerId) }
            .distinctUntilChanged()
    }

    override suspend fun retry(key: MessageKey) {
        if (key.kind == MessageKind.MMS) manager.retry(key.providerId)
    }

    override fun replacementFor(key: MessageKey): MessageKey? =
        if (key.kind != MessageKind.MMS) null else store.replacement(key.providerId)?.let { MessageKey(MessageKind.MMS, it) }

    private suspend fun defaultState(id: Long): MmsDownloadState {
        val info = persister.notificationInfo(id)
        return if (info?.messageType == MessageType.NOTIFICATION_IND) {
            MmsDownloadState.Failed(FailureReasons.encode(Failure.MMS_NOT_DOWNLOADED), 0)
        } else {
            MmsDownloadState.Done
        }
    }
}
