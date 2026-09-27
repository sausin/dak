package app.dak.ui.fraud

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.dak.R
import app.dak.safety.helplines.Helpline
import java.util.Locale

/**
 * Helpline names and purposes in the app language. The helplines bundle (`assets/helplines-v1.json`, signed for
 * over-the-air updates and identical to `shared/formats/helplines-v1.json`) stays English; its entries are translated
 * here by [Helpline.id] as `helpline_<id>_name` / `_purpose` strings, whose English text is the bundle's.
 *
 * A translation is used only while the bundle still says what was translated: when an update changes an entry's
 * English text (a new number, a new purpose), the bundle's own text is shown until the strings catch up, so a stale
 * translation never contradicts the current entry. Unknown ids (new entries) show the bundle's text too.
 */
internal class HelplineTexts(private val local: Resources, private val english: Resources) {

    fun name(helpline: Helpline): String = pick(helpline.name, STRINGS[helpline.id]?.first)

    fun purpose(helpline: Helpline): String = pick(helpline.purpose, STRINGS[helpline.id]?.second)

    private fun pick(bundled: String, res: Int?): String {
        if (res == null) return bundled
        val source = runCatching { english.getString(res) }.getOrNull() ?: return bundled
        return if (source == bundled) local.getString(res) else bundled
    }

    companion object {
        /** Bundled helpline id → (name, purpose) strings. `HelplineTextTest` checks ids and English text against the bundle. */
        val STRINGS: Map<String, Pair<Int, Int>> = mapOf(
            "in-cybercrime-1930" to (R.string.helpline_in_cybercrime_1930_name to R.string.helpline_in_cybercrime_1930_purpose),
            "in-cybercrime-portal" to (R.string.helpline_in_cybercrime_portal_name to R.string.helpline_in_cybercrime_portal_purpose),
            "in-chakshu" to (R.string.helpline_in_chakshu_name to R.string.helpline_in_chakshu_purpose),
            "in-trai-1909-sms" to (R.string.helpline_in_trai_1909_sms_name to R.string.helpline_in_trai_1909_sms_purpose),
            "in-trai-1909-call" to (R.string.helpline_in_trai_1909_call_name to R.string.helpline_in_trai_1909_call_purpose),
            "in-emergency-112" to (R.string.helpline_in_emergency_112_name to R.string.helpline_in_emergency_112_purpose),
            "in-rbi-cms" to (R.string.helpline_in_rbi_cms_name to R.string.helpline_in_rbi_cms_purpose),
            "in-rbi-contact-centre" to (R.string.helpline_in_rbi_contact_centre_name to R.string.helpline_in_rbi_contact_centre_purpose),
            "in-rbi-sachet" to (R.string.helpline_in_rbi_sachet_name to R.string.helpline_in_rbi_sachet_purpose),
        )

        fun create(context: Context): HelplineTexts {
            // The unqualified res/values strings are the English source.
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
            return HelplineTexts(context.resources, context.createConfigurationContext(config).resources)
        }
    }
}

@Composable
internal fun rememberHelplineTexts(): HelplineTexts {
    val context = LocalContext.current
    return remember(context) { HelplineTexts.create(context) }
}
