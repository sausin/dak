package app.dak.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceTextScaleTest {

    private fun assertScale(expected: Float, widthDp: Int) =
        assertEquals(expected, deviceTextScale(widthDp), 0.001f, "at ${widthDp}dp")

    @Test
    fun typicalPhonesAreTheBaseline() {
        assertScale(1f, 360)
        assertScale(1f, 393)
        assertScale(1f, 411) // emulator "Medium Phone"
        assertScale(1f, 412)
    }

    @Test
    fun largePhonesGrowGently() {
        assertScale(1.04f, 430)
        assertScale(1.08f, 448) // Pixel Pro XL class
        assertTrue(deviceTextScale(480) in 1.08f..1.12f)
    }

    @Test
    fun foldablesAndTabletsAreCapped() {
        assertScale(1.12f, 600)
        assertScale(1.12f, 673)
        assertScale(1.12f, 800)
        assertScale(1.12f, 1280)
    }

    @Test
    fun smallPhonesShrinkSlightlyWithAFloor() {
        assertScale(0.96f, 340)
        assertScale(0.92f, 320)
        assertScale(0.92f, 240)
    }

    @Test
    fun undefinedWidthIsNeutral() {
        assertScale(1f, 0)
        assertScale(1f, -1)
    }

    @Test
    fun scaleNeverDecreasesWithWidth() {
        var previous = deviceTextScale(200)
        for (dp in 201..1400) {
            val current = deviceTextScale(dp)
            assertTrue(current >= previous, "dropped at ${dp}dp")
            previous = current
        }
    }

    @Test
    fun scalingTouchesOnlySpUnits() {
        val style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.05.em)
        val scaled = style.scaled(1.5f)
        assertSp(24f, scaled.fontSize)
        assertSp(36f, scaled.lineHeight)
        assertEquals(0.05.em, scaled.letterSpacing)
        assertEquals(TextUnit.Unspecified, TextStyle().scaled(1.5f).fontSize)
    }

    private fun assertSp(expected: Float, actual: TextUnit) {
        assertTrue(actual.isSp)
        assertEquals(expected, actual.value, 0.001f)
    }
}
