package app.dak.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import app.dak.ui.theme.DakTheme

/**
 * Round avatar: contact photo when [photoUri] is set, else initials of [name] on a stable colour derived from
 * [key], else a person icon (numbers) or a storefront icon (alphanumeric business senders such as "VM-HDFCBK").
 */
@Composable
fun Avatar(
    name: String?,
    key: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    photoUri: String? = null,
    isBusiness: Boolean = false,
) {
    val colors = DakTheme.colors.avatar(key)
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(colors.container),
        contentAlignment = Alignment.Center,
    ) {
        val initials = initialsOf(name)
        when {
            photoUri != null -> AsyncImage(
                model = photoUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
            isBusiness -> Icon(Icons.Outlined.Storefront, contentDescription = null, tint = colors.content, modifier = Modifier.size(size * 0.55f))
            initials != null -> Text(initials, color = colors.content, style = MaterialTheme.typography.titleMedium)
            else -> Icon(Icons.Filled.Person, contentDescription = null, tint = colors.content, modifier = Modifier.size(size * 0.6f))
        }
    }
}

/** Up to two initials from a display name; null when the name is blank or looks like a phone number. */
fun initialsOf(name: String?): String? {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed.none { it.isLetter() }) return null
    val words = trimmed.split(Regex("\\s+")).filter { w -> w.firstOrNull()?.isLetter() == true }
    if (words.isEmpty()) return null
    val first = words.first().first().uppercaseChar()
    val second = words.drop(1).lastOrNull()?.first()?.uppercaseChar()
    return if (second != null) "$first$second" else "$first"
}
