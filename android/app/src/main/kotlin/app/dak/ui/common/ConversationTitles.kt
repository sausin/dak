package app.dak.ui.common

import android.content.res.Resources
import android.icu.text.ListFormatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.dak.R
import app.dak.index.ConversationSummary
import app.dak.index.SearchHit
import app.dak.telephony.mms.MmsUnknownSender

/**
 * The title shown for a conversation in the app language. The index cannot see app resources, so it hands over a
 * plain title plus, for a group, its participants ([ConversationSummary.titleParts]): those are joined with the
 * language's list pattern ("Asha, Ravi and +91…", "Asha, Ravi y +91…"), and an MMS filed under the placeholder
 * sender ([MmsUnknownSender]) is named with `unknown_sender` rather than shown as "Unknown".
 */
object ConversationTitles {

    fun display(resources: Resources, title: String, address: String, parts: List<String>): String {
        val unknown = { resources.getString(R.string.unknown_sender) }
        return when {
            parts.size > 1 -> ListFormatter.getInstance(resources.configuration.locales[0])
                .format(parts.map { if (MmsUnknownSender.isUnknown(it)) unknown() else it })
            title == address && MmsUnknownSender.isUnknown(address) -> unknown()
            title.isBlank() -> unknown()
            else -> title
        }
    }

    fun display(resources: Resources, summary: ConversationSummary): String =
        display(resources, summary.title, summary.address, summary.titleParts)

    fun display(resources: Resources, hit: SearchHit): String =
        display(resources, hit.conversationTitle, hit.message.address, hit.conversationTitleParts)
}

/** [ConversationTitles.display] in the composition's language. */
@Composable
@ReadOnlyComposable
fun conversationTitle(summary: ConversationSummary): String {
    LocalConfiguration.current // recompose when the configuration (language) changes, like stringResource
    return ConversationTitles.display(LocalContext.current.resources, summary)
}

/** [ConversationTitles.display] for a search hit, in the composition's language. */
@Composable
@ReadOnlyComposable
fun conversationTitle(hit: SearchHit): String {
    LocalConfiguration.current
    return ConversationTitles.display(LocalContext.current.resources, hit)
}
