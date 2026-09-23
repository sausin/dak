package app.dak.index.maintenance

import app.dak.index.bin.RecycleBin
import app.dak.index.repo.AuditLogRepository
import app.dak.index.signature.AppSignatureRegistry
import app.dak.index.sync.ProviderReconciler
import javax.inject.Inject

/** Purges expired recycle-bin entries (OTP 1 day, others 30 days by default) and trims the audit log. */
class BinPurgeTask @Inject constructor(
    private val recycleBin: RecycleBin,
    private val audit: AuditLogRepository,
) : MaintenanceTask {
    override val name: String get() = "bin-purge"
    override val order: Int get() = 10

    override suspend fun run() {
        recycleBin.purgeExpired()
        audit.trim()
    }
}

/**
 * The daily safety-net reconcile with the provider (missed changes and deletions). Live changes are handled by
 * the ContentObserver while the process runs; this replaces the former 6-hourly periodic worker.
 */
class ProviderReconcileTask @Inject constructor(private val reconciler: ProviderReconciler) : MaintenanceTask {
    override val name: String get() = "provider-reconcile"
    override val order: Int get() = 20

    override suspend fun run() {
        reconciler.full()
    }
}

/**
 * Incremental refresh of the SMS Retriever app-hash table (only packages installed or updated since the last run
 * are hashed). An unknown hash on an incoming OTP still triggers an on-demand refresh.
 */
class SignatureRefreshTask @Inject constructor(private val signatures: AppSignatureRegistry) : MaintenanceTask {
    override val name: String get() = "app-signatures"
    override val order: Int get() = 30

    override suspend fun run() {
        signatures.refresh()
    }
}
