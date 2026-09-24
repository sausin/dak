package app.dak.ui.conversation

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.index.repo.ScheduledSend
import java.util.Calendar

/**
 * Messages scheduled from this thread that have not gone out yet, just above the composer: when each goes and a
 * preview, tap to edit it in the scheduled list ([onOpen]), ✕ to cancel. At most [MAX_SHOWN] rows, then "N more".
 */
@Composable
fun ScheduledStrip(items: List<ScheduledSend>, onOpen: (Long) -> Unit, onCancel: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 2.dp)) {
            for (item in items.take(MAX_SHOWN)) {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(item.id) }.padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.sch_strip_when, whenText(context, item.sendAtMillis)),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(item.body, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onCancel(item.id) }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.sch_cancel))
                    }
                }
            }
            val more = items.size - MAX_SHOWN
            if (more > 0) {
                Text(
                    pluralStringResource(R.plurals.sch_strip_more, more, more),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(items[MAX_SHOWN].id) }.padding(horizontal = 44.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** "Tomorrow, 9:00 am" / "Fri, 12 Oct, 6:30 pm" in the device's locale and 12/24-hour setting. */
private fun whenText(context: Context, millis: Long): String {
    val flags = DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL
    val day = DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL)
    val relativeDay = millis - System.currentTimeMillis() < 2 * DateUtils.DAY_IN_MILLIS
    return if (relativeDay) "$day, ${DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)}" else DateUtils.formatDateTime(context, millis, flags)
}

/**
 * Platform date then time pickers for a send-later time, from today up to a year ahead. A time that is not at least a
 * minute away is refused with a hint rather than silently sent now.
 */
fun pickFutureDateTime(context: Context, onPicked: (Long) -> Unit) {
    val start = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + DEFAULT_OFFSET_MILLIS }
    val dateDialog = DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val picked = Calendar.getInstance().apply {
                        set(year, month, day, hour, minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    if (picked < System.currentTimeMillis() + MIN_AHEAD_MILLIS) {
                        Toast.makeText(context, R.string.sch_pick_future, Toast.LENGTH_SHORT).show()
                    } else {
                        onPicked(picked)
                    }
                },
                start.get(Calendar.HOUR_OF_DAY),
                start.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(context),
            ).show()
        },
        start.get(Calendar.YEAR),
        start.get(Calendar.MONTH),
        start.get(Calendar.DAY_OF_MONTH),
    )
    dateDialog.datePicker.minDate = System.currentTimeMillis() - 1_000L
    dateDialog.datePicker.maxDate = System.currentTimeMillis() + MAX_AHEAD_MILLIS
    dateDialog.show()
}

private const val MAX_SHOWN = 2
private const val DEFAULT_OFFSET_MILLIS = 60L * 60 * 1000
private const val MIN_AHEAD_MILLIS = 60L * 1000
private const val MAX_AHEAD_MILLIS = 365L * 24 * 60 * 60 * 1000
