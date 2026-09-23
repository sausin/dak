package app.dak.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settings registry is a persisted format: keys are DataStore keys, deep links and settings-export JSON keys;
 * choice values are stored verbatim; defaults decide what an upgrade user sees. This golden pins every row. A
 * rename, removal, type change, default change or option change fails here: if it is deliberate, add a migration
 * for stored values (renames) and update the golden in the same change.
 */
class SettingsGoldenTest {

    @Test
    fun `registry matches the golden list`() {
        val actual = DakSettings.all.map { describe(it) }
        val missing = GOLDEN - actual.toSet()
        val added = actual - GOLDEN.toSet()
        assertTrue(missing.isEmpty() && added.isEmpty(), "registry drifted.\nno longer present:\n${missing.joinToString("\n")}\nnew/changed:\n${added.joinToString("\n")}")
        assertEquals(GOLDEN, actual, "registry order changed (drives listing and search tie-breaks)")
    }

    @Test
    fun `every SettingDef declared on DakSettings is registered in all`() {
        // A row declared but forgotten in `all` is never searchable, exportable or reset.
        val declared = DakSettings::class.java.declaredFields
            .filter { SettingDef::class.java.isAssignableFrom(it.type) }
            .map { f -> f.isAccessible = true; (f.get(DakSettings) as SettingDef<*>).key }
        assertEquals(declared.toSet(), DakSettings.all.map { it.key }.toSet())
        assertEquals(declared.size, DakSettings.all.size)
    }

    @Test
    fun `keys are dotted by their group`() {
        val prefix = mapOf(
            SettingsGroup.NOTIFICATIONS to "notifications.",
            SettingsGroup.CATEGORIES_SPAM to "categoriesSpam.",
            SettingsGroup.FINANCE to "finance.",
            SettingsGroup.SIMS_SENDING to "simsSending.",
            SettingsGroup.BACKUP_DATA to "backupData.",
            SettingsGroup.AUTOMATIONS to "automations.",
            SettingsGroup.TRANSLATION to "translation.",
        )
        // The privacy rows live in the Backup, data and privacy group under their own `privacy.` namespace.
        val offenders = DakSettings.all
            .filterNot { it.key.startsWith(prefix.getValue(it.group)) || (it.group == SettingsGroup.BACKUP_DATA && it.key.startsWith("privacy.")) }
            .map { it.key }
        assertTrue(offenders.isEmpty(), "keys not prefixed by their group: $offenders")
        DakSettings.all.forEach { assertTrue(Regex("[a-z][A-Za-z]*(\\.[a-z][A-Za-z0-9]*)+").matches(it.key), it.key) }
    }

    @Test
    fun `defaults are legal values of their own control`() {
        DakSettings.all.forEach { def ->
            when (val c = def.control) {
                is ControlType.Slider -> {
                    val v = def.default as Int
                    assertTrue(v in c.range, "${def.key} default $v outside ${c.range}")
                    assertTrue((v - c.range.first) % c.step == 0, "${def.key} default $v not on a ${c.step} step")
                    assertTrue(c.step > 0)
                }
                is ControlType.SingleChoice -> {
                    val values = c.options.map { it.value }
                    assertTrue(def.default in values, "${def.key} default ${def.default} not an option")
                    assertEquals(values.size, values.toSet().size, "${def.key} duplicate option values")
                    assertTrue(c.options.all { it.label.isNotBlank() }, def.key)
                }
                ControlType.Toggle -> assertTrue(def.default is Boolean, def.key)
                else -> Unit
            }
        }
    }

    @Test
    fun `sliders reject out-of-range and non-numeric values`() {
        DakSettings.all.forEach { def ->
            val c = def.control as? ControlType.Slider ?: return@forEach
            assertEquals(null, def.deserialize((c.range.last + 1).toString()), def.key)
            assertEquals(null, def.deserialize((c.range.first - 1).toString()), def.key)
            assertEquals(null, def.deserialize("ten"), def.key)
            assertEquals(null, def.deserialize(""), def.key)
            assertEquals(c.range.first, def.deserialize(c.range.first.toString()), def.key)
            assertEquals(c.range.last, def.deserialize(c.range.last.toString()), def.key)
        }
    }

    @Test
    fun `importing an out-of-range slider value keeps the default`() {
        // A crafted or stale export must not set a 0-minute consumed-OTP window or lift the cloud cap.
        val store = InMemorySettingsStore()
        store.import("""{"notifications.consumedOtpWindowMinutes":"0","categoriesSpam.jevMonthlyCap":"999999"}""")
        assertEquals(DakSettings.consumedOtpWindowMinutes.default, store.get(DakSettings.consumedOtpWindowMinutes))
        assertEquals(DakSettings.jevMonthlyCap.default, store.get(DakSettings.jevMonthlyCap))
        assertEquals("{}", store.export())
        // And a corrupt stored value reads as the default too.
        val seeded = InMemorySettingsStore(mapOf(DakSettings.consumedOtpWindowMinutes.key to "-5"))
        assertEquals(DakSettings.consumedOtpWindowMinutes.default, seeded.get(DakSettings.consumedOtpWindowMinutes))
    }

    @Test
    fun `booleans are strict`() {
        val def = DakSettings.quickActions
        for (bad in listOf("TRUE", "True", "1", "yes", " true", "")) assertEquals(null, def.deserialize(bad), bad)
        assertEquals(true, def.deserialize("true"))
        assertEquals(false, def.deserialize("false"))
    }

    @Test
    fun `choices only accept listed values, case-sensitively`() {
        val def = DakSettings.otpDisplaySize
        assertEquals("large", def.deserialize("large"))
        assertEquals(null, def.deserialize("LARGE"))
        assertEquals(null, def.deserialize("huge"))
    }

    @Test
    fun `every non-action setting round trips a non-default value through export and import`() {
        val store = InMemorySettingsStore()
        val expected = mutableMapOf<String, String>()
        DakSettings.all.forEach { def ->
            val raw = when (val c = def.control) {
                ControlType.Action -> return@forEach
                ControlType.Toggle -> (!(def.default as Boolean)).toString()
                ControlType.Text -> "value-${def.key}"
                is ControlType.Slider -> c.range.last.toString()
                is ControlType.SingleChoice -> c.options.last().value
            }
            expected[def.key] = raw
            setRaw(store, def, raw)
        }
        val exported = Json.parseToJsonElement(store.export()) as JsonObject
        assertEquals(expected, exported.mapValues { it.value.jsonPrimitive.content })
        val fresh = InMemorySettingsStore()
        fresh.import(store.export())
        DakSettings.all.filter { it.control != ControlType.Action }.forEach { def ->
            assertEquals(store.get(def), fresh.get(def), def.key)
        }
    }

    @Test
    fun `import skips non-primitive values and non-object roots`() {
        val store = InMemorySettingsStore()
        store.import("""{"finance.homeCurrency":{"nested":"AED"},"notifications.quickActions":["false"]}""")
        store.import("""["finance.homeCurrency","AED"]""")
        store.import("")
        assertEquals("{}", store.export())
        // A JSON number or boolean primitive is accepted by content.
        store.import("""{"notifications.quickActions":false,"notifications.consumedOtpWindowMinutes":15}""")
        assertEquals(false, store.get(DakSettings.quickActions))
        assertEquals(15, store.get(DakSettings.consumedOtpWindowMinutes))
    }

    @Test
    fun `reset group leaves every other group alone`() {
        SettingsGroup.entries.forEach { target ->
            val store = InMemorySettingsStore()
            DakSettings.all.filter { it.control == ControlType.Toggle }.forEach { setRaw(store, it, (!(it.default as Boolean)).toString()) }
            store.resetGroup(target)
            val remaining = (Json.parseToJsonElement(store.export()) as JsonObject).keys
            assertTrue(remaining.none { DakSettings.byKey(it)!!.group == target }, "$target not reset")
            val expectedOthers = DakSettings.all.filter { it.control == ControlType.Toggle && it.group != target }.map { it.key }.toSet()
            assertEquals(expectedOthers, remaining, "$target reset touched other groups")
        }
    }

    @Test
    fun `observe emits the default, then each change, then the default after reset`() = runBlocking {
        val store = InMemorySettingsStore()
        val def = DakSettings.consumedOtpWindowMinutes
        assertEquals(def.default, store.observe(def).first())
        store.set(def, 25)
        assertEquals(listOf(25), store.observe(def).take(1).toList())
        store.resetGroup(def.group)
        assertEquals(def.default, store.observe(def).first())
    }

    @Test
    fun `byKey and byGroup agree with all`() {
        DakSettings.all.forEach { assertEquals(it, DakSettings.byKey(it.key)) }
        assertEquals(null, DakSettings.byKey("notifications.doesNotExist"))
        assertEquals(DakSettings.all.size, SettingsGroup.entries.sumOf { DakSettings.byGroup(it).size })
    }

    @Suppress("UNCHECKED_CAST")
    private fun setRaw(store: SettingsStore, def: SettingDef<*>, raw: String) {
        val typed = def as SettingDef<Any?>
        store.set(typed, typed.deserialize(raw) ?: error("${def.key} rejects $raw"))
    }

    companion object {
        /** One stable line per setting: everything about it that is persisted or exported. */
        fun <T> describe(def: SettingDef<T>): String {
            val control = when (val c = def.control) {
                ControlType.Toggle -> "toggle"
                ControlType.Text -> "text"
                ControlType.Action -> "action"
                is ControlType.Slider -> "slider(${c.range.first}..${c.range.last}/${c.step})"
                is ControlType.SingleChoice -> "choice(${c.options.joinToString(",") { it.value }})"
            }
            val tier = when (val t = def.tier) {
                SettingTier.Free -> "free"
                is SettingTier.Premium -> "premium:${t.feature.name}"
            }
            return "${def.key}|${def.group.name}|$control|default=${def.serialize(def.default)}|$tier"
        }

        private val GOLDEN: List<String> = listOf(
            "notifications.perCategoryAlerts|NOTIFICATIONS|action|default=|free",
            "notifications.channels|NOTIFICATIONS|action|default=|free",
            "notifications.otpDisplaySize|NOTIFICATIONS|choice(normal,large)|default=large|free",
            "notifications.otpAutoCopy|NOTIFICATIONS|toggle|default=true|free",
            "notifications.otpAutoDelete|NOTIFICATIONS|choice(1h,24h,off)|default=24h|free",
            "notifications.consumedOtpHandling|NOTIFICATIONS|choice(silentAutoDelete,silentOnly,normal)|default=silentAutoDelete|free",
            "notifications.consumedOtpWindowMinutes|NOTIFICATIONS|slider(5..60/5)|default=10|free",
            "notifications.quickActions|NOTIFICATIONS|toggle|default=true|free",
            "notifications.selfTest|NOTIFICATIONS|action|default=|free",
            "notifications.soundPerSim|NOTIFICATIONS|action|default=|free",
            "notifications.bubbles|NOTIFICATIONS|toggle|default=false|free",
            "notifications.lockScreenPrivacy|NOTIFICATIONS|choice(full,hideContent,hideAll)|default=hideContent|free",
            "categoriesSpam.tabSet|CATEGORIES_SPAM|action|default=|free",
            "categoriesSpam.swipeRight|CATEGORIES_SPAM|choice(archive,delete,markRead,pin,none)|default=archive|free",
            "categoriesSpam.swipeLeft|CATEGORIES_SPAM|choice(archive,delete,markRead,pin,none)|default=delete|free",
            "categoriesSpam.inboxOtpCopy|CATEGORIES_SPAM|toggle|default=true|free",
            "categoriesSpam.senderMerges|CATEGORIES_SPAM|action|default=|free",
            "categoriesSpam.blockList|CATEGORIES_SPAM|action|default=|free",
            "categoriesSpam.autoArchivePromosDays|CATEGORIES_SPAM|slider(0..90/1)|default=30|free",
            "categoriesSpam.fakeCreditWarnings|CATEGORIES_SPAM|toggle|default=true|free",
            "categoriesSpam.classifierConfidenceThreshold|CATEGORIES_SPAM|slider(0..100/5)|default=70|free",
            "categoriesSpam.jevOptIn|CATEGORIES_SPAM|toggle|default=false|free",
            "categoriesSpam.jevMonthlyCap|CATEGORIES_SPAM|slider(0..2000/50)|default=100|free",
            "finance.accounts|FINANCE|action|default=|free",
            "finance.homeCurrency|FINANCE|text|default=|free",
            "finance.hideBalancesOnLock|FINANCE|toggle|default=false|free",
            "finance.ratesSource|FINANCE|choice(ecb,openSource)|default=ecb|free",
            "finance.reconciliationToleranceMinor|FINANCE|slider(0..5000/50)|default=500|free",
            "simsSending.sim1.name|SIMS_SENDING|text|default=SIM 1|free",
            "simsSending.sim1.color|SIMS_SENDING|text|default=#3B82F6|free",
            "simsSending.sim2.name|SIMS_SENDING|text|default=SIM 2|free",
            "simsSending.sim2.color|SIMS_SENDING|text|default=#F97316|free",
            "simsSending.defaultReplySim|SIMS_SENDING|choice(sim1,sim2,ask)|default=ask|free",
            "simsSending.numberNormalization|SIMS_SENDING|toggle|default=true|free",
            "simsSending.roamingWarnings|SIMS_SENDING|toggle|default=true|free",
            "simsSending.costWarnings|SIMS_SENDING|toggle|default=true|free",
            "simsSending.enterToSend|SIMS_SENDING|toggle|default=false|free",
            "simsSending.deliveryReports|SIMS_SENDING|toggle|default=false|free",
            "simsSending.mmsReadReceipts|SIMS_SENDING|toggle|default=false|free",
            "simsSending.mmsDeliveryToSenders|SIMS_SENDING|toggle|default=true|free",
            "simsSending.sendRateSpreading|SIMS_SENDING|toggle|default=true|free",
            "simsSending.exactAlarmPermission|SIMS_SENDING|action|default=|free",
            "simsSending.broadcastLists|SIMS_SENDING|action|default=|free",
            "backupData.destination|BACKUP_DATA|action|default=|free",
            "backupData.schedule|BACKUP_DATA|choice(daily,weekly,manual)|default=daily|free",
            "backupData.encryptionKeyRecovery|BACKUP_DATA|action|default=|free",
            "backupData.export|BACKUP_DATA|action|default=|free",
            "backupData.import|BACKUP_DATA|action|default=|free",
            "backupData.otpBinRetention|BACKUP_DATA|choice(shorter,1day,longer,untilEmptied)|default=1day|premium:ADJUSTABLE_OTP_BIN_RETENTION",
            "backupData.otherBinRetentionDays|BACKUP_DATA|slider(30..30/1)|default=30|free",
            "backupData.binBiometricLock|BACKUP_DATA|toggle|default=false|free",
            "backupData.binExcludedFromBackup|BACKUP_DATA|toggle|default=true|free",
            "privacy.center|BACKUP_DATA|action|default=|free",
            "privacy.policy|BACKUP_DATA|action|default=|free",
            "privacy.dataSharing|BACKUP_DATA|action|default=|free",
            "privacy.exportMyData|BACKUP_DATA|action|default=|free",
            "privacy.deleteMyData|BACKUP_DATA|action|default=|free",
            "privacy.appLock|BACKUP_DATA|choice(off,device,appPin)|default=off|free",
            "privacy.autoLockAfter|BACKUP_DATA|choice(immediately,30s,1m,5m,15m)|default=1m|free",
            "privacy.lockOnScreenOff|BACKUP_DATA|toggle|default=true|free",
            "privacy.hideInRecents|BACKUP_DATA|choice(whenLockOn,always,never)|default=whenLockOn|free",
            "privacy.protectSensitiveScreens|BACKUP_DATA|toggle|default=false|free",
            "backupData.indexSchedule|BACKUP_DATA|choice(now,plugged,tonight)|default=tonight|free",
            "backupData.rebuildIndex|BACKUP_DATA|action|default=|free",
            "automations.rulesList|AUTOMATIONS|action|default=|free",
            "automations.scheduledSends|AUTOMATIONS|action|default=|free",
            "automations.scheduledHeadsUp|AUTOMATIONS|choice(off,5,15,60)|default=15|free",
            "automations.forwarding|AUTOMATIONS|action|default=|free",
            "automations.birthdayWishes|AUTOMATIONS|action|default=|free",
            "automations.webhooks|AUTOMATIONS|action|default=|premium:WEBHOOKS",
            "automations.sendApiKeys|AUTOMATIONS|action|default=|premium:SEND_API",
            "automations.auditLog|AUTOMATIONS|action|default=|premium:WEBHOOKS",
            "translation.languages|TRANSLATION|action|default=|premium:TRANSLATION",
            "translation.autoTranslateRules|TRANSLATION|action|default=|premium:TRANSLATION",
            "translation.downloadedPacks|TRANSLATION|action|default=|premium:TRANSLATION",
            "translation.freeTasterPack|TRANSLATION|toggle|default=false|free",
            "translation.packStorageLocation|TRANSLATION|action|default=|premium:TRANSLATION",
        )
    }
}
