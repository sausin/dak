package app.dak.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.dak.R
import app.dak.core.model.Category
import app.dak.core.model.SimInfo
import app.dak.ui.theme.DakTheme
import app.dak.ui.theme.TonalColors

/** Small rounded label used by every chip below. */
@Composable
fun TokenChip(
    label: String,
    colors: TonalColors,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = colors.container,
        contentColor = colors.content,
    ) {
        Row(
            modifier = clickable.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            leading?.invoke()
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/**
 * SIM chip in the SIM's slot colour. [compact] shows only "1"/"2" (thread rows); otherwise the SIM's name.
 * Removed SIMs ([SimInfo.isActive] false) are greyed.
 */
@Composable
fun SimChip(sim: SimInfo, modifier: Modifier = Modifier, compact: Boolean = false, onClick: (() -> Unit)? = null) {
    val slotLabel = if (sim.slotIndex >= 0) (sim.slotIndex + 1).toString() else "?"
    val label = when {
        compact -> slotLabel
        sim.displayName.isNotBlank() -> sim.displayName
        else -> stringResource(R.string.sim_n, slotLabel)
    }
    TokenChip(
        label = label,
        colors = DakTheme.colors.sim(sim.slotIndex),
        modifier = modifier.alpha(if (sim.isActive) 1f else 0.5f),
        leading = { Icon(Icons.Outlined.SimCard, contentDescription = null, modifier = Modifier.size(14.dp)) },
        onClick = onClick,
    )
}

/** Category chip in the category's semantic colour. */
@Composable
fun CategoryChip(category: Category, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    TokenChip(label = categoryLabel(category), colors = DakTheme.colors.category(category), modifier = modifier, onClick = onClick)
}

/** "Premium" lock chip shown on locked rows and features. */
@Composable
fun LockChip(modifier: Modifier = Modifier, label: String = stringResource(R.string.premium)) {
    val c = MaterialTheme.colorScheme
    TokenChip(
        label = label,
        colors = TonalColors(container = c.surfaceContainerHighest, content = c.onSurfaceVariant, accent = c.outline),
        modifier = modifier,
        leading = { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp)) },
    )
}

/** Localised display name of a category (tab titles, chips, notification channels use the same strings). */
@Composable
fun categoryLabel(category: Category): String = stringResource(categoryLabelRes(category))

/** String resource for a category's display name. */
fun categoryLabelRes(category: Category): Int = when (category) {
    Category.PERSONAL -> R.string.category_personal
    Category.TRANSACTION -> R.string.category_transactions
    Category.OTP -> R.string.category_otp
    Category.PROMOTION -> R.string.category_promotions
    Category.SPAM -> R.string.category_spam
    Category.UNKNOWN -> R.string.category_other
}
