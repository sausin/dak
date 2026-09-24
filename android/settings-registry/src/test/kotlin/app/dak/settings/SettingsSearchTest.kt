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

    @Test
    fun `privacy rows are free, action rows and findable by the words people use`() {
        val privacyRows = listOf(
            DakSettings.privacyCenter, DakSettings.privacyPolicy, DakSettings.dataSharingChoices,
            DakSettings.exportMyData, DakSettings.deleteMyData,
        )
        privacyRows.forEach {
            assertEquals(SettingTier.Free, it.tier, it.key)
            assertEquals(ControlType.Action, it.control, it.key)
            assertFalse(it.advanced, it.key)
        }
        assertTrue(SettingsSearch.search("privacy policy", context, FreeEntitlements).any { it.key == DakSettings.privacyPolicy.key })
        assertTrue(SettingsSearch.search("delete my data", context, FreeEntitlements).any { it.key == DakSettings.deleteMyData.key })
        assertTrue(SettingsSearch.search("consent", context, FreeEntitlements).any { it.key == DakSettings.dataSharingChoices.key })
        assertTrue(SettingsSearch.search("gdpr", context, FreeEntitlements).any { it.key == DakSettings.exportMyData.key })
    }

    @Test
    fun `privacy action rows are not exported as settings values`() {
        val store = InMemorySettingsStore()
        store.set(DakSettings.jevOptIn, true)
        val exported = store.export()
        assertFalse("privacy.center" in exported)
        assertTrue("categoriesSpam.jevOptIn" in exported)
    }

    /** Stand-in for the app's resource-backed text in another language (Hindi here). */
    private val hindi = object : SettingsText {
        override fun title(def: SettingDef<*>): String =
            if (def.key == DakSettings.otpAutoDelete.key) "ओटीपी अपने-आप हटाएँ" else def.title
        override fun summary(def: SettingDef<*>): String =
            if (def.key == DakSettings.otpAutoDelete.key) "आने के बाद ओटीपी संदेश अपने-आप हटाएँ।" else def.summary
    }

    @Test
    fun `localized titles and summaries are searchable`() {
        assertEquals(DakSettings.otpAutoDelete.key, SettingsSearch.search("ओटीपी", context, FreeEntitlements, text = hindi).first().key)
        assertTrue(SettingsSearch.search("संदेश", context, FreeEntitlements, text = hindi).any { it.key == DakSettings.otpAutoDelete.key })
    }

    @Test
    fun `english titles and keywords still match in another language`() {
        val results = SettingsSearch.search("otp auto", context, FreeEntitlements, text = hindi)
        assertEquals(DakSettings.otpAutoDelete.key, results.first().key)
        assertTrue(SettingsSearch.search("one time password", context, FreeEntitlements, text = hindi).any { it.key == DakSettings.otpAutoDelete.key })
        assertTrue(SettingsSearch.search("expire", context, FreeEntitlements, text = hindi).any { it.key == DakSettings.otpAutoDelete.key })
    }

    @Test
    fun `english text resolver ranks exactly like the default`() {
        for (q in listOf("otp", "sim", "lock", "backup", "balanc")) {
            assertEquals(
                SettingsSearch.search(q, context, FreeEntitlements).map { it.key },
                SettingsSearch.search(q, context, FreeEntitlements, text = SettingsText.English).map { it.key },
            )
        }
    }

    @Test
    fun `string keys are stable, valid resource names`() {
        assertEquals("setting_notifications_otpAutoDelete_title", SettingsStringKeys.title(DakSettings.otpAutoDelete))
        assertEquals("setting_simsSending_sim1_name_summary", SettingsStringKeys.summary(DakSettings.sim1Name))
        val opt = (DakSettings.otpAutoDelete.control as ControlType.SingleChoice).options.first()
        assertEquals("setting_notifications_otpAutoDelete_opt_1h", SettingsStringKeys.option(DakSettings.otpAutoDelete, opt))
        val swipe = SwipeActions.options.first()
        assertEquals("swipe_action_archive", SettingsStringKeys.option(DakSettings.swipeLeft, swipe))
        assertEquals(SettingsStringKeys.option(DakSettings.swipeLeft, swipe), SettingsStringKeys.option(DakSettings.swipeRight, swipe))
        assertEquals("settings_group_categories_spam", SettingsStringKeys.group(SettingsGroup.CATEGORIES_SPAM))
        assertEquals(SettingsStringKeys.all().size, SettingsStringKeys.all().toSet().size)
    }
}
