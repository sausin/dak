package app.dak.security

import app.dak.settings.ControlType
import app.dak.settings.DakSettings
import app.dak.settings.SettingDef
import kotlin.test.Test
import kotlin.test.assertEquals

/** The registry rows and the lock's enums must agree on stored values and defaults. */
class AppLockSettingsContractTest {
    private fun options(def: SettingDef<*>) = (def.control as ControlType.SingleChoice).options.map { it.value }

    @Test
    fun `lock method values and default match the registry`() {
        assertEquals(LockMethodChoice.entries.map { it.value }, options(DakSettings.appLock))
        assertEquals(LockMethodChoice.DEFAULT, LockMethodChoice.fromValue(DakSettings.appLock.default))
    }

    @Test
    fun `auto lock values and default match the registry`() {
        assertEquals(AutoLockTimeout.entries.map { it.value }, options(DakSettings.autoLockAfter))
        assertEquals(AutoLockTimeout.DEFAULT, AutoLockTimeout.fromValue(DakSettings.autoLockAfter.default))
    }

    @Test
    fun `recents values and default match the registry`() {
        assertEquals(RecentsProtection.entries.map { it.value }, options(DakSettings.hideInRecents))
        assertEquals(RecentsProtection.DEFAULT, RecentsProtection.fromValue(DakSettings.hideInRecents.default))
    }

    @Test
    fun `config defaults match the registry defaults`() {
        val defaults = AppLockConfig()
        assertEquals(DakSettings.lockOnScreenOff.default, defaults.lockOnScreenOff)
        assertEquals(DakSettings.protectSensitiveScreens.default, defaults.protectSensitiveScreens)
    }
}
