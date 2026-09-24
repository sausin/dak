package app.dak.flavor

import app.dak.premium.Entitlements
import app.dak.premium.StaticEntitlements
import app.dak.premium.NoOpPremiumGateway
import app.dak.premium.NoOpQueryUnderstanding
import app.dak.premium.NoOpTranslator
import app.dak.premium.PremiumGateway
import app.dak.premium.QueryUnderstanding
import app.dak.premium.Translator
import app.dak.premium.consent.ConsentGatedPremiumGateway
import app.dak.premium.consent.ConsentGatedQueryUnderstanding
import app.dak.premium.consent.ConsentLedger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Premium flavour bindings. Premium: Play Billing and server-backed implementations replace these as they land (Phase 3). */
@Module
@InstallIn(SingletonComponent::class)
object TierModule {
    @Provides @Singleton
    fun entitlements(): Entitlements = StaticEntitlements(emptySet()) // TODO(phase-3): Play Billing

    /**
     * Every server-backed call goes through the consent gate: nothing leaves the phone without a current consent for
     * that data flow (Settings → Privacy). Replace only the inner implementation when the real gateway lands.
     */
    @Provides @Singleton
    fun premiumGateway(consents: ConsentLedger): PremiumGateway = ConsentGatedPremiumGateway(NoOpPremiumGateway, consents::isGranted)

    @Provides @Singleton
    fun translator(): Translator = NoOpTranslator

    @Provides @Singleton
    fun queryUnderstanding(consents: ConsentLedger): QueryUnderstanding =
        ConsentGatedQueryUnderstanding(NoOpQueryUnderstanding, consents::isGranted)
}
