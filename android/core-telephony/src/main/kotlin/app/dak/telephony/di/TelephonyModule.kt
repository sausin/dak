package app.dak.telephony.di

import android.content.Context
import app.dak.telephony.BlockedNumbers
import app.dak.telephony.IncomingMessageHandler
import app.dak.telephony.MessageSender
import app.dak.telephony.MmsDownloads
import app.dak.telephony.NumberNormalizer
import app.dak.telephony.ProviderChanges
import app.dak.telephony.ProviderReader
import app.dak.telephony.ProviderWriter
import app.dak.telephony.SimRepository
import app.dak.telephony.blocked.TelephonyBlockedNumbers
import app.dak.telephony.boot.OutboxRecovery
import app.dak.telephony.mms.MmsDownloadManager
import app.dak.telephony.mms.MmsSendManager
import app.dak.telephony.mms.TelephonyMmsDownloads
import app.dak.telephony.mms.WapPushProcessor
import app.dak.telephony.number.TelephonyNumberNormalizer
import app.dak.telephony.provider.TelephonyProviderChanges
import app.dak.telephony.provider.TelephonyProviderReader
import app.dak.telephony.provider.TelephonyProviderWriter
import app.dak.telephony.region.RegionProvider
import app.dak.telephony.region.TelephonyRegionProvider
import app.dak.telephony.send.SendRateLimiter
import app.dak.telephony.send.TelephonyMessageSender
import app.dak.telephony.sim.TelephonySimRepository
import app.dak.telephony.sms.IncomingSmsProcessor
import app.dak.telephony.sms.SmsStatusProcessor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application-lifetime scope for receiver work (`goAsync`) and shared flows. IO dispatcher, supervisor job. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TelephonyScope

/** Binds every :core-telephony implementation to its contract. */
@Module
@InstallIn(SingletonComponent::class)
abstract class TelephonyBindingsModule {
    @Binds
    abstract fun bindProviderReader(impl: TelephonyProviderReader): ProviderReader

    @Binds
    abstract fun bindProviderWriter(impl: TelephonyProviderWriter): ProviderWriter

    @Binds
    abstract fun bindProviderChanges(impl: TelephonyProviderChanges): ProviderChanges

    @Binds
    abstract fun bindSimRepository(impl: TelephonySimRepository): SimRepository

    @Binds
    abstract fun bindMessageSender(impl: TelephonyMessageSender): MessageSender

    @Binds
    abstract fun bindMmsDownloads(impl: TelephonyMmsDownloads): MmsDownloads

    @Binds
    abstract fun bindBlockedNumbers(impl: TelephonyBlockedNumbers): BlockedNumbers

    @Binds
    abstract fun bindNumberNormalizer(impl: TelephonyNumberNormalizer): NumberNormalizer

    @Binds
    abstract fun bindRegionProvider(impl: TelephonyRegionProvider): RegionProvider

    /** Declared so an app with no handlers still gets a valid (empty) set. Contribute with `@IntoSet`. */
    @Multibinds
    abstract fun incomingMessageHandlers(): Set<IncomingMessageHandler>
}

@Module
@InstallIn(SingletonComponent::class)
object TelephonyProvidesModule {
    @Provides
    @Singleton
    @TelephonyScope
    fun provideTelephonyScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideSendRateLimiter(): SendRateLimiter = SendRateLimiter()
}

/**
 * Entry point used by manifest components (receivers, services, workers), which are instantiated by the system
 * rather than injected. Internal plumbing: app code should inject the contract interfaces instead.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TelephonyEntryPoint {
    fun incomingSmsProcessor(): IncomingSmsProcessor
    fun smsStatusProcessor(): SmsStatusProcessor
    fun wapPushProcessor(): WapPushProcessor
    fun mmsDownloadManager(): MmsDownloadManager
    fun mmsSendManager(): MmsSendManager
    fun messageSender(): TelephonyMessageSender
    fun outboxRecovery(): OutboxRecovery

    @TelephonyScope
    fun telephonyScope(): CoroutineScope
}

/** Resolves [TelephonyEntryPoint] from the application's Hilt component. */
object TelephonyEntryPoints {
    fun get(context: Context): TelephonyEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, TelephonyEntryPoint::class.java)
}
