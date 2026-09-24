package app.dak.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryExemptionTest {

    @Test
    fun anAppTheUserExemptedIsExempt() {
        assertTrue(BatteryOptimization.exempt(ignoringOptimizations = true, defaultSmsApp = false))
    }

    @Test
    fun theDefaultSmsAppIsExemptEvenWhenNotOnTheDeviceIdleList() {
        // Settings shows the default SMS app's battery option as allowed and locked, while
        // PowerManager.isIgnoringBatteryOptimizations stays false: a warning there could never be fixed.
        assertTrue(BatteryOptimization.exempt(ignoringOptimizations = false, defaultSmsApp = true))
    }

    @Test
    fun anOptimizedNonDefaultAppIsNotExempt() {
        assertFalse(BatteryOptimization.exempt(ignoringOptimizations = false, defaultSmsApp = false))
    }
}
