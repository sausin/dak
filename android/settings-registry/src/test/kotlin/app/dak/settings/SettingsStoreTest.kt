package app.dak.settings

import app.dak.premium.Feature
import app.dak.premium.FreeEntitlements
import app.dak.premium.StaticEntitlements
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStoreTest {

    @Test
    fun `unset value returns the default`() {
        val store = InMemorySettingsStore()
        assertEquals(DakSettings.quickActions.default, store.get(DakSettings.quickActions))
        assertEquals(DakSettings.homeCurrency.default, store.get(DakSettings.homeCurrency))
    }

    @Test
    fun `set then get round trips`() {
        val store = InMemorySettingsStore()
        store.set(DakSettings.quickActions, false)
        assertEquals(false, store.get(DakSettings.quickActions))

        store.set(DakSettings.homeCurrency, "AED")
        assertEquals("AED", store.get(DakSettings.homeCurrency))

        store.set(DakSettings.consumedOtpWindowMinutes, 15)
        assertEquals(15, store.get(DakSettings.consumedOtpWindowMinutes))
    }

    @Test
    fun `observe emits current value and updates`() = runBlocking {
        val store = InMemorySettingsStore()
        assertEquals(DakSettings.bubbles.default, store.observe(DakSettings.bubbles).first())
        store.set(DakSettings.bubbles, true)
        assertEquals(true, store.observe(DakSettings.bubbles).first())
    }

    @Test
    fun `reset group clears only that group's overrides`() {
        val store = InMemorySettingsStore()
        store.set(DakSettings.quickActions, false) // Notifications
        store.set(DakSettings.homeCurrency, "AED") // Finance

        store.resetGroup(SettingsGroup.NOTIFICATIONS)

        assertEquals(DakSettings.quickActions.default, store.get(DakSettings.quickActions))
        assertEquals("AED", store.get(DakSettings.homeCurrency))
    }

    @Test
    fun `export only includes overridden non-action settings`() {
        val store = InMemorySettingsStore()
        store.set(DakSettings.quickActions, false)
        val json = store.export()
        assertTrue(json.contains(DakSettings.quickActions.key))
        assertFalse(json.contains(DakSettings.rebuildIndex.key)) // Action rows are never exported
        assertFalse(json.contains(DakSettings.homeCurrency.key)) // untouched, still default
    }

    @Test
    fun `export then import round trips into a fresh store`() {
        val store = InMemorySettingsStore()
        store.set(DakSettings.quickActions, false)
        store.set(DakSettings.homeCurrency, "AED")
        store.set(DakSettings.consumedOtpWindowMinutes, 20)

        val json = store.export()
        val fresh = InMemorySettingsStore()
        fresh.import(json)

        assertEquals(false, fresh.get(DakSettings.quickActions))
        assertEquals("AED", fresh.get(DakSettings.homeCurrency))
        assertEquals(20, fresh.get(DakSettings.consumedOtpWindowMinutes))
    }

    @Test
    fun `import ignores unknown keys`() {
        val fresh = InMemorySettingsStore()
        fresh.import("""{"nonexistent.key": "value", "${DakSettings.homeCurrency.key}": "USD"}""")
        assertEquals("USD", fresh.get(DakSettings.homeCurrency))
    }

    @Test
    fun `import ignores values that fail type checking`() {
        val fresh = InMemorySettingsStore()
        // quickActions is a Boolean; "notABoolean" must not parse and must not be stored.
        fresh.import("""{"${DakSettings.quickActions.key}": "notABoolean"}""")
        assertEquals(DakSettings.quickActions.default, fresh.get(DakSettings.quickActions))
    }

    @Test
    fun `import tolerates malformed json`() {
        val fresh = InMemorySettingsStore()
        fresh.import("not json at all")
        assertEquals(DakSettings.quickActions.default, fresh.get(DakSettings.quickActions))
    }

    @Test
    fun `locked reflects entitlements for a premium setting`() {
        val store = InMemorySettingsStore()
        assertTrue(store.locked(DakSettings.otpBinRetention, FreeEntitlements))
        assertFalse(store.locked(DakSettings.otpBinRetention, StaticEntitlements(setOf(Feature.ADJUSTABLE_OTP_BIN_RETENTION))))
    }

    @Test
    fun `locked is always false for a free setting`() {
        val store = InMemorySettingsStore()
        assertFalse(store.locked(DakSettings.quickActions, FreeEntitlements))
    }
}
