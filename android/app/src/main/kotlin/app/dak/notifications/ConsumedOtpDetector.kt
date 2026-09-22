package app.dak.notifications

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import app.dak.classify.AppSignatureHash
import app.dak.classify.ConsumedOtpMatcher
import app.dak.core.model.OtpInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether an OTP was meant for an installed app's automatic read (SMS Retriever hash) or a browser
 * (WebOTP), so its notification can be quiet. Hashes are computed for every launchable app visible through the
 * manifest `<queries>` and cached; the cache is rebuilt when a hash is not found and the cache is older than
 * [REFRESH_AFTER_MILLIS] (covers apps installed since).
 */
@Singleton
class ConsumedOtpDetector @Inject constructor(@ApplicationContext private val context: Context) {

    private companion object {
        const val REFRESH_AFTER_MILLIS = 15 * 60_000L
    }

    @Volatile private var matcher: ConsumedOtpMatcher? = null
    @Volatile private var builtAt = 0L

    /** Package that consumed [otp], or null when it should be treated as a normal (loud) OTP. */
    fun consumerOf(otp: OtpInfo): String? {
        if (otp.retrieverHash == null && otp.webOtpDomain == null) return null
        val now = System.currentTimeMillis()
        var m = matcher
        if (m == null) m = rebuild(now)
        val found = m.consumerOf(otp)
        if (found != null || now - builtAt < REFRESH_AFTER_MILLIS) return found
        return rebuild(now).consumerOf(otp)
    }

    /** Human-readable label of [packageName] (for "Used by Google Pay"), falling back to the package name. */
    fun labelOf(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    @Suppress("DEPRECATION")
    @Synchronized
    private fun rebuild(now: Long): ConsumedOtpMatcher {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).map { it.activityInfo.packageName }.toSet() - context.packageName
        val computed = HashMap<String, String>(launchable.size)
        for (pkg in launchable) {
            val signature = signingCertificate(pm, pkg) ?: continue
            computed[pkg] = AppSignatureHash.compute(pkg, signature.toByteArray())
        }
        val browsers = pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.MATCH_ALL,
        ).map { it.activityInfo.packageName }.toSet()
        builtAt = now
        return ConsumedOtpMatcher(computed, browsers).also { matcher = it }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificate(pm: PackageManager, pkg: String): Signature? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info: PackageInfo = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return null
            // The retriever hash is derived from the current signer.
            if (signing.hasMultipleSigners()) signing.apkContentsSigners.firstOrNull()
            else signing.signingCertificateHistory.lastOrNull()
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
