package app.dak.settings

import app.dak.premium.Feature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DakSettingsTest {
    @Test
    fun `keys are unique`() {
        val keys = DakSettings.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate keys: ${keys.groupBy { it }.filterValues { it.size > 1 }.keys}")
    }

    @Test
    fun `every group is represented`() {
        SettingsGroup.entries.forEach { group ->
            assertTrue(DakSettings.byGroup(group).isNotEmpty(), "group $group has no settings")
        }
    }

    @Test
    fun `groups are ordered by frequency of use as in the spec table`() {
        assertEquals(
            listOf(
                SettingsGroup.NOTIFICATIONS, SettingsGroup.CATEGORIES_SPAM, SettingsGroup.FINANCE,
                SettingsGroup.SIMS_SENDING, SettingsGroup.BACKUP_DATA, SettingsGroup.AUTOMATIONS,
                SettingsGroup.TRANSLATION,
            ),
            SettingsGroup.entries,
        )
    }

    @Test
    fun `otp bin retention is the one premium-gated setting from the free vs premium table`() {
        val def = DakSettings.otpBinRetention
        assertEquals(SettingTier.Premium(Feature.ADJUSTABLE_OTP_BIN_RETENTION), def.tier)
    }

    @Test
    fun `translation rows are premium except the free taster`() {
        assertTrue(DakSettings.translationLanguages.tier is SettingTier.Premium)
        assertTrue(DakSettings.autoTranslateRules.tier is SettingTier.Premium)
        assertTrue(DakSettings.downloadedPacks.tier is SettingTier.Premium)
        assertEquals(SettingTier.Free, DakSettings.freeTasterPack.tier)
    }

    @Test
    fun `automation webhooks and send api are premium`() {
        assertTrue(DakSettings.webhooks.tier is SettingTier.Premium)
        assertTrue(DakSettings.sendApiKeys.tier is SettingTier.Premium)
        assertEquals(SettingTier.Free, DakSettings.rulesList.tier)
    }

    @Test
    fun `sim2 rows hidden on single-sim device context`() {
        val singleSim = DeviceContext(simCount = 1, hasMmsData = true, apiLevel = 34)
        assertTrue(!DakSettings.sim2Name.visible(singleSim))
        assertTrue(!DakSettings.sim2Color.visible(singleSim))
        assertTrue(!DakSettings.defaultReplySim.visible(singleSim))
        assertTrue(!DakSettings.soundPerSim.visible(singleSim))

        val dualSim = singleSim.copy(simCount = 2)
        assertTrue(DakSettings.sim2Name.visible(dualSim))
        assertTrue(DakSettings.defaultReplySim.visible(dualSim))
    }

    @Test
    fun `exact alarm permission hidden below api 31`() {
        val old = DeviceContext(simCount = 1, hasMmsData = true, apiLevel = 26)
        val new = old.copy(apiLevel = 33)
        assertTrue(!DakSettings.exactAlarmPermission.visible(old))
        assertTrue(DakSettings.exactAlarmPermission.visible(new))
    }

    @Test
    fun `bin biometric lock hidden without biometric hardware`() {
        val noBiometric = DeviceContext(simCount = 1, hasMmsData = true, apiLevel = 34, hasBiometric = false)
        assertTrue(!DakSettings.binBiometricLock.visible(noBiometric))
    }

    @Test
    fun `otp related settings carry otp synonyms`() {
        val otpKeywords = setOf("one time password", "code", "verification")
        assertTrue(DakSettings.otpDisplaySize.keywords.any { it in otpKeywords })
        assertTrue(DakSettings.otpAutoDelete.keywords.any { it in otpKeywords })
    }

    @Test
    fun `every setting has a non-blank summary and title`() {
        DakSettings.all.forEach { def ->
            assertTrue(def.title.isNotBlank(), "blank title for ${def.key}")
            assertTrue(def.summary.isNotBlank(), "blank summary for ${def.key}")
        }
    }

    @Test
    fun `default values round trip through serialize and deserialize`() {
        DakSettings.all.forEach { def ->
            roundTripDefault(def)
        }
    }

    private fun <T> roundTripDefault(def: SettingDef<T>) {
        val serialized = def.serialize(def.default)
        assertEquals(def.default, def.deserialize(serialized), "round trip failed for ${def.key}")
    }
}
