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
import app.dak.index.repo.RatesSource
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

    /** Opens (or recreates) the encrypted index; first injection does Keystore + file I/O. */
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
    ): DefaultMessageEnricher {
        val lookup = contacts.orElse(NoContactLookup)
        return DefaultMessageEnricher(
            isContact = { address -> lookup.isContact(address) },
            cloud = cloud.orElse(NoCloudClassifier),
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
