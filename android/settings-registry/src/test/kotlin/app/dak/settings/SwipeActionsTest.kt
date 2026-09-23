package app.dak.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwipeActionsTest {
    @Test
    fun `swipe defaults are archive right and delete left`() {
        assertEquals(SwipeActions.ARCHIVE, DakSettings.swipeRight.default)
        assertEquals(SwipeActions.DELETE, DakSettings.swipeLeft.default)
    }

    @Test
    fun `swipe settings accept every option and reject unknown values`() {
        for (option in SwipeActions.options) {
            assertEquals(option.value, DakSettings.swipeLeft.deserialize(option.value))
        }
        assertNull(DakSettings.swipeRight.deserialize("explode"))
    }

    @Test
    fun `ux rows are registered and searchable`() {
        listOf(DakSettings.swipeRight, DakSettings.swipeLeft, DakSettings.inboxOtpCopy, DakSettings.enterToSend).forEach {
            assertTrue(it in DakSettings.all, "${it.key} missing from DakSettings.all")
        }
        val hits = SettingsSearch.search(
            "swipe",
            DeviceContext(simCount = 1, hasMmsData = true, apiLevel = 34, hasBiometric = false),
            app.dak.premium.FreeEntitlements,
        ).map { it.key }
        assertTrue(DakSettings.swipeRight.key in hits && DakSettings.swipeLeft.key in hits, "search hits: $hits")
    }
}
