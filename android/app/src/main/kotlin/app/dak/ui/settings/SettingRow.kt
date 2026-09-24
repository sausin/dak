package app.dak.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import app.dak.settings.ControlType
import app.dak.ui.common.LockChip
import app.dak.ui.theme.DakTheme
import kotlinx.coroutines.delay

/**
 * One Settings row: title, one-line summary, inline current value, and a trailing control.
 * Locked (premium) rows are greyed with a lock chip and their one-line reason; tapping them calls [onClick]
 * (which opens the upgrade sheet). [highlighted] flashes the row background (deep-link focus).
 */
@Composable
fun SettingRow(
    row: RowState,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    sectionLabel: String? = null,
) {
    var flash by remember(row.key, highlighted) { mutableStateOf(highlighted) }
    LaunchedEffect(row.key, highlighted) {
        if (highlighted) {
            delay(2_400)
            flash = false
        }
    }
    val background by animateColorAsState(
        targetValue = if (flash) DakTheme.colors.focusHighlight else Color.Transparent,
        animationSpec = tween(durationMillis = 600),
        label = "settingFocus",
    )
    val isToggle = row.def.control is ControlType.Toggle
    val checked = row.raw.toBooleanStrictOrNull() == true

    ListItem(
        modifier = modifier
            .background(background)
            // A toggle row is one TalkBack node with a switch state ("on"/"off"), not a button plus a switch.
            .then(
                if (isToggle && !row.locked) {
                    Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
                } else {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                },
            ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        overlineContent = if (sectionLabel != null) { { Text(sectionLabel) } } else null,
        headlineContent = { Text(row.title, modifier = Modifier.alpha(if (row.locked) 0.6f else 1f)) },
        supportingContent = {
            Column(Modifier.alpha(if (row.locked) 0.6f else 1f)) {
                Text(row.lockReason ?: row.summary, style = MaterialTheme.typography.bodyMedium)
                val value = row.valueLabel
                if (!row.locked && !isToggle && value != null) {
                    Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        trailingContent = {
            when {
                row.locked -> LockChip()
                isToggle -> Switch(checked = checked, onCheckedChange = null)
                row.def.control is ControlType.Action ->
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                else -> Unit
            }
        },
    )
}
