package app.dak.ui.settings

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.settings.ControlType
import kotlin.math.roundToInt

/** Editor dialog for a row, chosen by its control type. Calls [onSave] with the new raw value. */
@Composable
fun SettingEditorDialog(row: RowState, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    when (val control = row.def.control) {
        is ControlType.SingleChoice -> ChoiceDialog(row, onDismiss, onSave)
        is ControlType.Slider -> SliderDialog(row, control, onDismiss, onSave)
        is ControlType.Text -> TextDialog(row, onDismiss, onSave)
        else -> LaunchedEffect(row.key) { onDismiss() }
    }
}

@Composable
private fun ChoiceDialog(row: RowState, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.def.title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                Text(row.def.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                row.def.choiceOptions().forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.RadioButton) { onSave(option.value) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option.value == row.raw, onClick = null)
                        Text(option.label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SliderDialog(row: RowState, control: ControlType.Slider, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val range = control.range
    val step = control.step.coerceAtLeast(1)
    var value by remember { mutableFloatStateOf((row.raw.toIntOrNull() ?: range.first).toFloat()) }
    val stepsBetween = ((range.last - range.first) / step - 1).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.def.title) },
        text = {
            Column {
                Text(row.def.summary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    value.roundToInt().toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                if (range.last > range.first) {
                    Slider(
                        value = value,
                        onValueChange = { value = it },
                        valueRange = range.first.toFloat()..range.last.toFloat(),
                        steps = stepsBetween,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val snapped = (((value.roundToInt() - range.first).toFloat() / step).roundToInt() * step + range.first)
                    .coerceIn(range.first, range.last)
                onSave(snapped.toString())
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun TextDialog(row: RowState, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(row.raw) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.def.title) },
        text = {
            Column {
                Text(row.def.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
