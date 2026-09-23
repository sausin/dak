package app.dak.settings

import app.dak.premium.Entitlements
import app.dak.premium.Feature
import app.dak.premium.FreeEntitlements
import app.dak.premium.StaticEntitlements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSearchTest {
    private val context = DeviceContext(simCount = 2, hasMmsData = true, apiLevel = 34)

    @Test
    fun `blank query returns nothing`() {
        assertTrue(SettingsSearch.search("", context, FreeEntitlements).isEmpty())
    }

    @Test
    fun `matches by synonym keyword not just title`() {
        val results = SettingsSearch.search("one time password", context, FreeEntitlements)
        assertTrue(results.any { it.key == DakSettings.otpAutoDelete.key })
        assertTrue(results.any { it.key == DakSettings.otpDisplaySize.key })
    }

    @Test
    fun `prefix match on title ranks first`() {
        val results = SettingsSearch.search("otp auto", context, FreeEntitlements)
        assertEquals(DakSettings.otpAutoDelete.key, results.first().key)
    }

    @Test
    fun `fuzzy match tolerates a typo`() {
        val results = SettingsSearch.search("balanc", context, FreeEntitlements) // typo for "balances"
        assertTrue(results.any { it.key == DakSettings.hideBalancesOnLock.key })
    }

    @Test
    fun `hidden rows for this device are excluded`() {
        val singleSim = context.copy(simCount = 1)
        val results = SettingsSearch.search("sim 2", singleSim, FreeEntitlements)
        assertFalse(results.any { it.key == DakSettings.sim2Name.key })
    }

    @Test
    fun `premium rows are included but flagged locked for free entitlements`() {
        val results = SettingsSearch.search("translate", context, FreeEntitlements)
        val hit = results.first { it.key == DakSettings.translationLanguages.key }
        assertTrue(hit.locked)
    }

    @Test
    fun `premium rows unlock once entitlements grant the feature`() {
        val entitlements: Entitlements = StaticEntitlements(setOf(Feature.TRANSLATION))
        val results = SettingsSearch.search("translate", context, entitlements)
        val hit = results.first { it.key == DakSettings.translationLanguages.key }
        assertFalse(hit.locked)
    }

    @Test
    fun `changed keys rank above equally-scored unchanged rows`() {
        val results = SettingsSearch.search(
            query = "otp",
            deviceContext = context,
            entitlements = FreeEntitlements,
            changedKeys = setOf(DakSettings.otpBinRetention.key),
        )
        val rankedKeys = results.map { it.key }
        val binIndex = rankedKeys.indexOf(DakSettings.otpBinRetention.key)
        assertTrue(binIndex == 0, "changed row should rank first, got order: $rankedKeys")
    }
}
