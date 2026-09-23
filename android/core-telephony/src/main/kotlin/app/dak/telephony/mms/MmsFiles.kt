package app.dak.telephony.mms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.ForegroundInfo
import app.dak.telephony.R
import java.io.File

/**
 * FileProvider for MMS PDU hand-off to the platform MmsService. Its own subclass (and authority
 * `${applicationId}.dak.mms`) so it never clashes with a FileProvider the app declares. Not exported; the
 * platform's MmsServiceBroker is granted per-URI access for each call (`grantUriPermissions`).
 */
class MmsFileProvider : FileProvider()

/** Temporary PDU files under `cacheDir/dak_mms`, exposed through [MmsFileProvider]. */
internal object MmsFiles {
    private const val DIR = "dak_mms"
    private const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

    fun authority(context: Context): String = context.packageName + ".dak.mms"

    fun directory(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    /** Creates an empty file for one transfer (the platform writes downloads into it). */
    fun newFile(context: Context, prefix: String): File {
        val dir = directory(context)
        pruneStale(dir)
        val file = File(dir, "$prefix-${System.nanoTime()}.pdu")
        file.createNewFile()
        return file
    }

    fun contentUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** Resolves a path we handed out earlier, refusing anything outside our directory. */
    fun resolve(context: Context, path: String?): File? {
        if (path.isNullOrEmpty()) return null
        val file = File(path)
        val dir = directory(context).canonicalPath + File.separator
        return if (file.canonicalPath.startsWith(dir)) file else null
    }

    private fun pruneStale(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}

/** The low-priority notification WorkManager needs when an expedited MMS download runs as a foreground service. */
internal object TransferNotifications {
    private const val CHANNEL_ID = "dak_mms_transfers"
    private const val NOTIFICATION_ID = 0x0D4C

    fun foregroundInfo(context: Context): ForegroundInfo {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.dak_telephony_mms_downloading))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.dak_telephony_channel_transfers),
            NotificationManager.IMPORTANCE_MIN,
        )
        manager.createNotificationChannel(channel)
    }
}
