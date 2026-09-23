package app.dak.index.signature

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import app.dak.classify.AppSignatureHash
import app.dak.core.model.OtpInfo
import app.dak.index.db.DakIndexDatabase
import app.dak.index.db.entity.AppSignatureRow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a table of SMS Retriever app hashes (Google's `AppSignatureHelper` algorithm, via
 * `app.dak.classify.AppSignatureHash`) for installed packages, so an incoming OTP ending in an 11-char hash can be
 * attributed to the app that auto-reads it (`consumedBy`).
 *
 * Package broadcasts (`PACKAGE_ADDED` / `PACKAGE_REPLACED`) cannot be received by a manifest receiver on Android 8+,
 * so the table refreshes lazily instead: built once on first start ([refreshIfNeverBuilt]), refreshed incrementally
 * (by `lastUpdateTime`) by the daily maintenance job, and whenever an OTP carries a hash that is not in the table
 * ([consumerOf]: throttled to once a minute, and a hash that stayed unknown after a refresh is not retried for
 * [NEGATIVE_TTL_MILLIS] - the sender is usually for an app that is simply not installed). The table is persisted,
 * so a process start never rescans installed apps. Package visibility on Android 11+ comes from the `<queries>`
 * entries in this module's manifest (launcher apps and browsers).
 */
@Singleton
class AppSignatureRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    db: DakIndexDatabase,
) {
    private val dao = db.appSignatureDao()
    private val mutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    @Volatile
    private var lastMissRefreshAt = 0L

    /**
     * Recomputes hashes for new or updated packages and drops uninstalled ones. Returns the number of packages
     * (re)computed. [force] recomputes everything.
     */
    suspend fun refresh(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val count = refreshLocked(force)
            val now = System.currentTimeMillis()
            val editor = prefs.edit().putLong(KEY_LAST_REFRESH, now)
            // New or updated packages may be the apps behind previously unknown hashes: forget those misses
            // (and always drop expired ones, so the file stays small).
            for ((name, value) in prefs.all) {
                if (!name.startsWith(UNKNOWN_PREFIX)) continue
                val expired = now - ((value as? Long) ?: 0L) >= NEGATIVE_TTL_MILLIS
                if (count > 0 || expired) editor.remove(name)
            }
            editor.apply()
            count
        }
    }

    /**
     * Builds the table if it has never been built on this install (first start, or after an index rebuild that
     * recreated the database); otherwise does nothing. Keeps the first OTP's receiver from paying for a full scan.
     */
    suspend fun refreshIfNeverBuilt(): Int = withContext(Dispatchers.IO) {
        val built = prefs.getLong(KEY_LAST_REFRESH, 0L) != 0L && dao.all().isNotEmpty()
        if (built) 0 else refresh()
    }

    /**
     * The package that most likely consumed [otp]: an exact retriever-hash match, else (for WebOTP) the first
     * installed browser. With [refreshOnMiss], an unknown hash triggers one throttled incremental refresh.
     */
    suspend fun consumerOf(otp: OtpInfo, refreshOnMiss: Boolean = true): String? = withContext(Dispatchers.IO) {
        val hash = otp.retrieverHash
        if (hash != null) {
            dao.packageForHash(hash)?.let { return@withContext it }
            val now = System.currentTimeMillis()
            // Persisted, so the negative answer survives the short-lived processes an SMS usually runs in.
            val recentlyMissed = now - prefs.getLong(UNKNOWN_PREFIX + hash, 0L) < NEGATIVE_TTL_MILLIS
            if (refreshOnMiss && !recentlyMissed && now - lastMissRefreshAt >= MISS_REFRESH_INTERVAL_MILLIS) {
                lastMissRefreshAt = now
                refresh()
                dao.packageForHash(hash)?.let { return@withContext it }
                prefs.edit().putLong(UNKNOWN_PREFIX + hash, now).apply()
            }
        }
        if (otp.webOtpDomain != null) {
            dao.browserPackages().firstOrNull()?.let { return@withContext it }
        }
        null
    }

    /** Hashes currently known for [packageName] (for diagnostics / settings). */
    suspend fun hashesOf(packageName: String): List<String> = withContext(Dispatchers.IO) {
        dao.all().filter { it.packageName == packageName }.map { it.hash }
    }

    private suspend fun refreshLocked(force: Boolean): Int {
        val pm = context.packageManager
        val installed = installedPackages(pm)
        val browsers = browserPackages(pm)
        val existing = dao.all().groupBy { it.packageName }
        val installedNames = installed.mapTo(HashSet<String>()) { it.packageName }

        val removed = existing.keys.filter { it !in installedNames }
        removed.chunked(CHUNK).forEach { dao.deletePackages(it) }

        val now = System.currentTimeMillis()
        val fresh = ArrayList<AppSignatureRow>()
        val stale = ArrayList<String>()
        for (info in installed) {
            val pkg = info.packageName
            val rows = existing[pkg]
            val isBrowser = pkg in browsers
            val unchanged = rows != null && rows.all { it.packageUpdatedAt == info.lastUpdateTime && it.isBrowser == isBrowser }
            if (!force && unchanged) continue
            val hashes = signatures(pm, pkg).map { AppSignatureHash.compute(pkg, it.toCharsString()) }.distinct()
            if (rows != null) stale += pkg
            hashes.mapTo(fresh) { AppSignatureRow(pkg, it, isBrowser, info.lastUpdateTime, now) }
        }
        stale.chunked(CHUNK).forEach { dao.deletePackages(it) }
        fresh.chunked(CHUNK).forEach { dao.putAll(it) }
        return fresh.map { it.packageName }.distinct().size
    }

    private fun installedPackages(pm: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

    private fun browserPackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return resolved.mapNotNullTo(HashSet<String>()) { it.activityInfo?.packageName }
    }

    private fun signatures(pm: PackageManager, packageName: String): List<Signature> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageInfo(pm, packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo
            when {
                signingInfo == null -> emptyList()
                signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners?.toList().orEmpty()
                else -> signingInfo.signingCertificateHistory?.toList().orEmpty()
            }
        } else {
            @Suppress("DEPRECATION")
            val info = packageInfo(pm, packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.toList().orEmpty()
        }
    } catch (e: PackageManager.NameNotFoundException) {
        emptyList()
    }

    private fun packageInfo(pm: PackageManager, packageName: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags)
        }

    private companion object {
        const val CHUNK = 200
        const val MISS_REFRESH_INTERVAL_MILLIS = 60_000L
        const val NEGATIVE_TTL_MILLIS = 6 * 60 * 60_000L
        const val UNKNOWN_PREFIX = "unknown:"
        const val PREFS = "dak_app_signatures"
        const val KEY_LAST_REFRESH = "lastRefresh"
    }
}
