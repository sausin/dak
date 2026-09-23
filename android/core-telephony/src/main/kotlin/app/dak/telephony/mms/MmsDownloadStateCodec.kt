package app.dak.telephony.mms

import app.dak.telephony.MmsDownloadState

/** Compact string form of [MmsDownloadState] for SharedPreferences. */
internal object MmsDownloadStateCodec {
    private const val PENDING = "p"
    private const val DOWNLOADING = "d"
    private const val DONE = "ok"
    private const val FAILED_PREFIX = "f|"

    fun encode(state: MmsDownloadState): String = when (state) {
        MmsDownloadState.Pending -> PENDING
        MmsDownloadState.Downloading -> DOWNLOADING
        MmsDownloadState.Done -> DONE
        is MmsDownloadState.Failed -> FAILED_PREFIX + state.attempts + "|" + state.reason
    }

    fun decode(value: String?): MmsDownloadState? = when {
        value == null -> null
        value == PENDING -> MmsDownloadState.Pending
        value == DOWNLOADING -> MmsDownloadState.Downloading
        value == DONE -> MmsDownloadState.Done
        value.startsWith(FAILED_PREFIX) -> {
            val rest = value.substring(FAILED_PREFIX.length)
            val sep = rest.indexOf('|')
            if (sep < 0) {
                null
            } else {
                val attempts = rest.substring(0, sep).toIntOrNull()
                attempts?.let { MmsDownloadState.Failed(rest.substring(sep + 1), it) }
            }
        }
        else -> null
    }
}
