package app.dak.di

import app.dak.index.BinPolicy
import app.dak.index.ContactLookup
import app.dak.index.OtpPolicy
import app.dak.notifications.MessageNotifier
import app.dak.telephony.IncomingMessageHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * App-provided implementations of library seams: :core-index policies (declared there with `@BindsOptionalOf`),
 * contacts, and the notification poster contributed to :core-telephony's incoming-message handler set.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {

    @Binds
    abstract fun binPolicy(impl: SettingsBinPolicy): BinPolicy

    @Binds
    abstract fun otpPolicy(impl: SettingsOtpPolicy): OtpPolicy

    @Binds
    abstract fun contactLookup(impl: AndroidContactLookup): ContactLookup

    @Binds @IntoSet
    abstract fun notificationHandler(impl: MessageNotifier): IncomingMessageHandler
}
