package app.dak.flavor

import app.dak.premium.Entitlements
import app.dak.premium.FreeEntitlements
import app.dak.premium.NoOpPremiumGateway
import app.dak.premium.NoOpQueryUnderstanding
import app.dak.premium.NoOpTranslator
import app.dak.premium.PremiumGateway
import app.dak.premium.QueryUnderstanding
import app.dak.premium.Translator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Free flavour bindings. Free binds no-ops; nothing else knows which flavour it is in. */
@Module
@InstallIn(SingletonComponent::class)
object TierModule {
    @Provides @Singleton
    fun entitlements(): Entitlements = FreeEntitlements

    @Provides @Singleton
    fun premiumGateway(): PremiumGateway = NoOpPremiumGateway

    @Provides @Singleton
    fun translator(): Translator = NoOpTranslator

    @Provides @Singleton
    fun queryUnderstanding(): QueryUnderstanding = NoOpQueryUnderstanding
}
