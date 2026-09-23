package app.dak.index.di

import android.content.Context
import app.dak.classify.CloudClassifier
import app.dak.classify.NoCloudClassifier
import app.dak.index.BinPolicy
import app.dak.index.ContactLookup
import app.dak.index.NoContactLookup
import app.dak.index.OtpPolicy
import app.dak.index.crypto.IndexDatabaseFactory
import app.dak.index.db.DakIndexDatabase
import app.dak.index.enrich.DefaultMessageEnricher
import app.dak.index.enrich.MessageEnricher
import app.dak.index.maintenance.BinPurgeTask
import app.dak.index.maintenance.MaintenanceTask
import app.dak.index.maintenance.ProviderReconcileTask
import app.dak.index.maintenance.SignatureRefreshTask
import app.dak.index.repo.RatesSource
import app.dak.index.scam.IndexScamContext
import app.dak.index.sync.IncomingIndexer
import app.dak.telephony.IncomingMessageHandler
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import java.util.Optional
import javax.inject.Singleton

/**
 * Hilt bindings of :core-index. Requires :core-telephony's bindings (`ProviderReader`, `ProviderWriter`,
 * `ProviderChanges`, `SimRepository`). Optional seams the app may bind: [BinPolicy], [OtpPolicy],
 * [ContactLookup], [RatesSource], [CloudClassifier] (each falls back to a documented default).
 */
@Module
@InstallIn(SingletonComponent::class)
object IndexProvidesModule {

    /**
     * The encrypted index. Cheap to inject on any thread: Keystore unwrap and the SQLCipher open are deferred to
     * the first real query, which Room runs on its background executor.
     */
    @Provides
    @Singleton
    fun openedIndex(@ApplicationContext context: Context): IndexDatabaseFactory.OpenedIndex =
        IndexDatabaseFactory.open(context)

    @Provides
    @Singleton
    fun database(opened: IndexDatabaseFactory.OpenedIndex): DakIndexDatabase = opened.database

    @Provides
    @Singleton
    fun defaultEnricher(
        contacts: Optional<ContactLookup>,
        cloud: Optional<CloudClassifier>,
        scamContext: IndexScamContext,
    ): DefaultMessageEnricher {
        val lookup = contacts.orElse(NoContactLookup)
        return DefaultMessageEnricher(
            isContact = { address -> lookup.isContact(address) },
            cloud = cloud.orElse(NoCloudClassifier),
            scamContext = scamContext,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class IndexBindsModule {

    @Binds
    abstract fun messageEnricher(impl: DefaultMessageEnricher): MessageEnricher

    @Binds
    @IntoSet
    abstract fun incomingIndexer(impl: IncomingIndexer): IncomingMessageHandler

    /** Daily housekeeping run by the single maintenance job; features add theirs with `@IntoSet`. */
    @Multibinds
    abstract fun maintenanceTasks(): Set<MaintenanceTask>

    @Binds
    @IntoSet
    abstract fun binPurgeTask(impl: BinPurgeTask): MaintenanceTask

    @Binds
    @IntoSet
    abstract fun providerReconcileTask(impl: ProviderReconcileTask): MaintenanceTask

    @Binds
    @IntoSet
    abstract fun signatureRefreshTask(impl: SignatureRefreshTask): MaintenanceTask

    @BindsOptionalOf
    abstract fun binPolicy(): BinPolicy

    @BindsOptionalOf
    abstract fun otpPolicy(): OtpPolicy

    @BindsOptionalOf
    abstract fun contactLookup(): ContactLookup

    @BindsOptionalOf
    abstract fun ratesSource(): RatesSource

    /** Bind only in the premium flavour; the implementation itself must honour the user's opt-in. */
    @BindsOptionalOf
    abstract fun cloudClassifier(): CloudClassifier
}
