package app.dak.index.scam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dak.core.model.MessageKey
import app.dak.core.model.MessageKind
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScamOverridesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences(ScamOverrides.PREFS, Context.MODE_PRIVATE)

    private fun sms(id: Long) = MessageKey(MessageKind.SMS, id)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun dismissalSurvivesANewInstance() {
        ScamOverrides(prefs).dismiss(sms(42))
        val reloaded = ScamOverrides(prefs)
        assertTrue(reloaded.isDismissed(sms(42)))
        assertFalse(reloaded.isDismissed(sms(43)))
    }

    @Test
    fun overTheBoundTheOldestDismissalsAreDropped() {
        val overrides = ScamOverrides(prefs, maxDismissed = 10)
        val total = 13L
        for (id in 1L..total) overrides.dismiss(sms(id))
        val reloaded = ScamOverrides(prefs, maxDismissed = 10)
        for (id in 1L..3L) assertFalse(reloaded.isDismissed(sms(id)), "oldest $id dropped")
        for (id in 4L..total) assertTrue(reloaded.isDismissed(sms(id)), "newer $id kept")
    }

    @Test
    fun dismissingAgainMakesTheKeyTheNewest() {
        val overrides = ScamOverrides(prefs, maxDismissed = 3)
        overrides.dismiss(sms(1))
        overrides.dismiss(sms(2))
        overrides.dismiss(sms(1))
        overrides.dismiss(sms(3))
        overrides.dismiss(sms(4))
        val reloaded = ScamOverrides(prefs, maxDismissed = 3)
        assertFalse(reloaded.isDismissed(sms(2)))
        assertTrue(reloaded.isDismissed(sms(1)))
    }

    @Test
    fun legacyUnorderedSetIsReadAndMigrated() {
        prefs.edit().putStringSet(ScamOverrides.KEY_LEGACY, setOf(sms(7).toString(), sms(8).toString())).commit()
        val overrides = ScamOverrides(prefs)
        assertTrue(overrides.isDismissed(sms(7)))
        overrides.dismiss(sms(9))
        val reloaded = ScamOverrides(prefs)
        assertTrue(reloaded.isDismissed(sms(7)) && reloaded.isDismissed(sms(8)) && reloaded.isDismissed(sms(9)))
        assertFalse(prefs.contains(ScamOverrides.KEY_LEGACY))
    }
}
