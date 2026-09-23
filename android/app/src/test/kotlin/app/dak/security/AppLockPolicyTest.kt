package app.dak.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockPolicyTest {
    private val immediate = AppLockConfig(method = LockMethodChoice.DEVICE, autoLock = AutoLockTimeout.IMMEDIATELY, lockOnScreenOff = false)
    private val oneMinute = immediate.copy(autoLock = AutoLockTimeout.MINUTE_1)

    @Test
    fun `setting values map both ways with safe defaults`() {
        AutoLockTimeout.entries.forEach { assertEquals(it, AutoLockTimeout.fromValue(it.value)) }
        LockMethodChoice.entries.forEach { assertEquals(it, LockMethodChoice.fromValue(it.value)) }
        RecentsProtection.entries.forEach { assertEquals(it, RecentsProtection.fromValue(it.value)) }
        assertEquals(AutoLockTimeout.DEFAULT, AutoLockTimeout.fromValue("bogus"))
        assertEquals(LockMethodChoice.OFF, LockMethodChoice.fromValue(null))
        assertEquals(listOf(0L, 30_000L, 60_000L, 300_000L, 900_000L), AutoLockTimeout.entries.map { it.millis })
    }

    @Test
    fun `effective lock falls back instead of locking the user out`() {
        assertEquals(EffectiveLock.NONE, AppLockRules.effectiveLock(LockMethodChoice.OFF, deviceSecure = true, hasAppPin = true))
        assertEquals(EffectiveLock.DEVICE, AppLockRules.effectiveLock(LockMethodChoice.DEVICE, deviceSecure = true, hasAppPin = false))
        assertEquals(EffectiveLock.APP_PIN, AppLockRules.effectiveLock(LockMethodChoice.DEVICE, deviceSecure = false, hasAppPin = true))
        assertEquals(EffectiveLock.NONE, AppLockRules.effectiveLock(LockMethodChoice.DEVICE, deviceSecure = false, hasAppPin = false))
        assertEquals(EffectiveLock.APP_PIN, AppLockRules.effectiveLock(LockMethodChoice.APP_PIN, deviceSecure = true, hasAppPin = true))
        // Restored settings on a new phone: app PIN chosen but none stored here.
        assertEquals(EffectiveLock.DEVICE, AppLockRules.effectiveLock(LockMethodChoice.APP_PIN, deviceSecure = true, hasAppPin = false))
        assertEquals(EffectiveLock.NONE, AppLockRules.effectiveLock(LockMethodChoice.APP_PIN, deviceSecure = false, hasAppPin = false))
    }

    @Test
    fun `confirmations prefer the chosen app pin then the device lock`() {
        assertEquals(EffectiveLock.APP_PIN, AppLockRules.confirmationLock(LockMethodChoice.APP_PIN, deviceSecure = true, hasAppPin = true))
        assertEquals(EffectiveLock.DEVICE, AppLockRules.confirmationLock(LockMethodChoice.OFF, deviceSecure = true, hasAppPin = true))
        assertEquals(EffectiveLock.APP_PIN, AppLockRules.confirmationLock(LockMethodChoice.OFF, deviceSecure = false, hasAppPin = true))
        assertEquals(EffectiveLock.NONE, AppLockRules.confirmationLock(LockMethodChoice.DEVICE, deviceSecure = false, hasAppPin = false))
    }

    @Test
    fun `secure window follows the recents choice and always covers the lock screen`() {
        val cfg = AppLockConfig()
        assertTrue(AppLockRules.secureWindow(cfg, lockActive = true, locked = false))
        assertFalse(AppLockRules.secureWindow(cfg, lockActive = false, locked = false))
        assertTrue(AppLockRules.secureWindow(cfg.copy(recents = RecentsProtection.ALWAYS), lockActive = false, locked = false))
        assertFalse(AppLockRules.secureWindow(cfg.copy(recents = RecentsProtection.NEVER), lockActive = true, locked = false))
        assertTrue(AppLockRules.secureWindow(cfg.copy(recents = RecentsProtection.NEVER), lockActive = true, locked = true))
    }

    @Test
    fun `fingerprint instead of pin needs a pin behind it`() {
        assertTrue(AppLockRules.fingerprintInsteadOfPinAvailable(hasAppPin = true, strongBiometricEnrolled = true))
        assertFalse(AppLockRules.fingerprintInsteadOfPinAvailable(hasAppPin = false, strongBiometricEnrolled = true))
        assertFalse(AppLockRules.fingerprintInsteadOfPinAvailable(hasAppPin = true, strongBiometricEnrolled = false))
    }

    @Test
    fun `new session starts locked when the lock is on`() {
        assertTrue(LockSession(initiallyLocked = true).locked)
        assertFalse(LockSession(initiallyLocked = false).locked)
    }

    @Test
    fun `immediate lock engages as soon as the app is backgrounded`() {
        val s = LockSession(initiallyLocked = false)
        s.onUnlocked()
        s.onBackground(1_000, immediate, lockActive = true)
        assertTrue(s.locked)
        assertFalse(s.sensitiveUnlocked)
    }

    @Test
    fun `timed lock engages only after the timeout in the background`() {
        val s = LockSession(initiallyLocked = false)
        s.onBackground(0, oneMinute, lockActive = true)
        assertFalse(s.locked)
        s.onForeground(59_999, oneMinute, lockActive = true)
        assertFalse(s.locked)
        s.onBackground(100_000, oneMinute, lockActive = true)
        s.onForeground(160_000, oneMinute, lockActive = true)
        assertTrue(s.locked)
    }

    @Test
    fun `foreground without a recorded background does nothing`() {
        val s = LockSession(initiallyLocked = false)
        s.onForeground(10_000_000, immediate, lockActive = true)
        assertFalse(s.locked)
    }

    @Test
    fun `screen off locks only when enabled`() {
        val s = LockSession(initiallyLocked = false)
        s.onScreenOff(oneMinute, lockActive = true)
        assertFalse(s.locked)
        s.onScreenOff(oneMinute.copy(lockOnScreenOff = true), lockActive = true)
        assertTrue(s.locked)
    }

    @Test
    fun `our own credential screen does not relock the app whatever order the result arrives in`() {
        // Result delivered before the foreground event.
        val a = LockSession(initiallyLocked = true)
        a.beginAuth()
        a.onBackground(0, immediate, lockActive = true)
        a.onUnlocked()
        a.endAuth()
        a.onForeground(5_000, immediate, lockActive = true)
        assertFalse(a.locked)

        // Result delivered after the foreground event.
        val b = LockSession(initiallyLocked = true)
        b.beginAuth()
        b.onBackground(0, immediate, lockActive = true)
        b.onForeground(5_000, immediate, lockActive = true)
        b.onUnlocked()
        b.endAuth()
        assertFalse(b.locked)
    }

    @Test
    fun `leaving the app for long during a prompt still locks`() {
        val s = LockSession(initiallyLocked = false)
        s.onUnlocked()
        s.beginAuth()
        s.onBackground(0, immediate, lockActive = true)
        assertFalse(s.locked)
        s.onForeground(LockSession.AUTH_GRACE_MILLIS, immediate, lockActive = true)
        assertTrue(s.locked)
        s.endAuth()
        assertFalse(s.authInProgress)
    }

    @Test
    fun `sensitive session expires like the lock even when the app lock is off`() {
        val s = LockSession(initiallyLocked = false)
        s.onSensitiveUnlocked()
        assertTrue(s.sensitiveUnlocked)
        s.onBackground(0, immediate, lockActive = false)
        assertFalse(s.locked)
        assertFalse(s.sensitiveUnlocked)
    }

    @Test
    fun `turning the lock off unlocks and turning it on does not lock the current screen`() {
        val s = LockSession(initiallyLocked = true)
        s.onPolicyChanged(lockActive = false)
        assertFalse(s.locked)
        s.onPolicyChanged(lockActive = true)
        assertFalse(s.locked)
        s.lockNow(lockActive = true)
        assertTrue(s.locked)
        s.onUnlocked()
        assertFalse(s.locked)
        assertTrue(s.sensitiveUnlocked)
    }

    @Test
    fun `end auth never goes negative`() {
        val s = LockSession(initiallyLocked = false)
        s.endAuth()
        s.beginAuth()
        assertTrue(s.authInProgress)
        s.endAuth()
        assertFalse(s.authInProgress)
    }
}
