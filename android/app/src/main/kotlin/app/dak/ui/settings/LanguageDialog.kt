package app.dak.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.i18n.AppLocales
import java.util.Locale

/**
 * Settings → Appearance → Language: "System default" plus every language in res/xml/locales_config.xml, each named
 * in its own language with the name in the current language under it. Picking one applies it at once (the screen
 * is recreated in the new language).
 */
@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val options = remember(context) { listOf<String?>(null) + AppLocales.supported(context) }
    val current = remember(context) { AppLocales.current(context)?.let(::normalized) }
    val uiLocale = AppLocales.primary(context.resources)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_appearance_language_title)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                options.forEach { tag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) {
                                onDismiss()
                                if (tag?.let(::normalized) != current) AppLocales.set(context, tag, context.findActivity())
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = tag?.let(::normalized) == current, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            if (tag == null) {
                                Text(stringResource(R.string.language_system_default))
                            } else {
                                Text(AppLocales.displayName(tag))
                                val local = Locale.forLanguageTag(tag).getDisplayName(uiLocale)
                                if (local != AppLocales.displayName(tag)) {
                                    Text(local, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun normalized(tag: String): String = Locale.forLanguageTag(tag).toLanguageTag()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
