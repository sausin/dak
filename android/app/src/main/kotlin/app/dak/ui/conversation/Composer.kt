package app.dak.ui.conversation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.dak.R
import app.dak.core.model.SimInfo
import app.dak.telephony.DefaultSmsRole
import app.dak.ui.common.SimChip
import app.dak.ui.common.TokenChip
import app.dak.ui.common.WarningBanner
import app.dak.ui.theme.DakTheme
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import app.dak.telephony.R as TelephonyR

/** Everything the composer shows besides the draft text; built by the screen's ViewModel. */
data class ComposerUi(
    /** Draft as last seen by the derived state (segments, MMS switch); the field itself binds to the live draft. */
    val text: String = "",
    val attachments: List<ComposerAttachment> = emptyList(),
    val isMms: Boolean = false,
    val segments: SegmentInfo = SegmentInfo(0, 0),
    /** Show the segment counter (over 3 segments, or while roaming). */
    val showSegments: Boolean = false,
    val sim: SimInfo? = null,
    /** More than one SIM: the SIM chip is a one-tap switcher. */
    val canSwitchSim: Boolean = false,
    val roaming: Boolean = false,
    /** "Sent as +91…" normalisation hint (shown the first few times only), or null. */
    val normalizedHint: String? = null,
    val sending: Boolean = false,
    val enabled: Boolean = true,
    /** A send waiting for a cost confirmation (premium / short code / international / roaming), or null. */
    val costPrompt: CostPrompt? = null,
    /**
     * Another app is the default SMS app: sending is paused (queued and scheduled messages wait) and the composer
     * offers to take the role back. [enabled] stays true only for a text to emergency numbers.
     */
    val notDefaultApp: Boolean = false,
    /** What the carrier's MMS rules do to this message, if anything worth telling. */
    val carrierNotice: CarrierNotice? = null,
    /** The carrier's MMS recipient limit, for [CarrierNotice.TOO_MANY_RECIPIENTS]. */
    val recipientLimit: Int? = null,
)

/** Carrier-config effects on the message being composed (see [app.dak.telephony.carrier.SendModePolicy]). */
enum class CarrierNotice {
    /** Group MMS is off for this carrier: each recipient gets their own message. */
    GROUP_AS_INDIVIDUAL,

    /** MMS is off for this carrier: attachments cannot be sent. */
    MMS_DISABLED,

    /** More recipients than one MMS may carry on this carrier. */
    TOO_MANY_RECIPIENTS,

    /** Text over the carrier's MMS text limit. */
    TEXT_TOO_LONG,
}

/** Callbacks from the composer to its ViewModel. */
interface ComposerActions {
    fun onTextChange(text: String)
    fun onAddAttachment(attachment: ComposerAttachment)
    fun onRemoveAttachment(attachment: ComposerAttachment)
    fun onSwitchSim()
    fun onSend()
    fun onScheduleSend(atMillis: Long)

    /** The user confirmed the pending [ComposerUi.costPrompt] send; [dontAskAgain] remembers these numbers. */
    fun onConfirmCost(dontAskAgain: Boolean) {}

    /** The user cancelled the pending [ComposerUi.costPrompt] send. */
    fun onDismissCost() {}

    /** The system "default SMS app" dialog closed (or the screen resumed): re-check the role. */
    fun onRoleResult() {}
}

/**
 * One composer for text and media: attachment tray (camera, gallery, files, contact card, location as a maps
 * link), automatic SMS→MMS switch shown as an "MMS" chip on the send button, segment counter when it matters,
 * one-tap reply-SIM switcher, roaming chip and the "sent as +91…" hint. Long-press send to send later.
 * With [enterToSend] the keyboard's action key sends (and shows a send icon) instead of adding a new line.
 * [focusRequester] lets the screen focus the field (swipe-to-reply). Back closes an open attachment tray first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Composer(
    ui: ComposerUi,
    text: String,
    actions: ComposerActions,
    modifier: Modifier = Modifier,
    enterToSend: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var trayOpen by rememberSaveable { mutableStateOf(false) }
    var laterMenu by remember { mutableStateOf(false) }
    BackHandler(enabled = trayOpen) { trayOpen = false }
    val canSend = ui.enabled && !ui.sending && (text.isNotBlank() || ui.attachments.isNotEmpty())
    ui.costPrompt?.let { prompt ->
        CostWarningDialog(prompt = prompt, onConfirm = actions::onConfirmCost, onDismiss = actions::onDismissCost)
    }
    // The role can be changed in system settings while Dak is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { actions.onRoleResult() }
    Column(modifier.fillMaxWidth()) {
        if (ui.notDefaultApp) NotDefaultAppBanner(onRoleResult = actions::onRoleResult)
        Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ComposerStatusRow(ui, actions)
                ui.carrierNotice?.let { CarrierNoticeText(it, ui.recipientLimit) }
                if (ui.attachments.isNotEmpty()) AttachmentStrip(ui.attachments, actions::onRemoveAttachment)
                if (trayOpen) AttachmentTray(onAttachment = actions::onAddAttachment, onText = { actions.onTextChange(joinText(text, it)) }, onDone = { trayOpen = false })
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { trayOpen = !trayOpen }, enabled = ui.enabled) {
                        Icon(if (trayOpen) Icons.Outlined.Close else Icons.Outlined.Add, contentDescription = stringResource(R.string.scr_composer_attach))
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = actions::onTextChange,
                        modifier = Modifier.weight(1f).let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
                        enabled = ui.enabled,
                        placeholder = { Text(stringResource(if (ui.isMms) R.string.scr_composer_hint_mms else R.string.scr_composer_hint_sms)) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default,
                        ),
                        keyboardActions = KeyboardActions(onSend = { if (canSend) actions.onSend() }),
                        maxLines = 6,
                        shape = RoundedCornerShape(24.dp),
                    )
                    Box {
                        SendButton(
                            isMms = ui.isMms,
                            enabled = canSend,
                            onClick = actions::onSend,
                            onLongClick = { laterMenu = true },
                        )
                        DropdownMenu(expanded = laterMenu, onDismissRequest = { laterMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.scr_composer_send_in_hour)) },
                                onClick = { laterMenu = false; actions.onScheduleSend(System.currentTimeMillis() + 60 * 60_000L) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.scr_composer_send_tomorrow)) },
                                onClick = { laterMenu = false; actions.onScheduleSend(tomorrowAtNine()) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shown above the composer while another app is the default SMS app: messages can be read, sending is paused
 * (queued and scheduled messages wait), and one tap asks the system to make Dak the default again.
 */
@Composable
private fun NotDefaultAppBanner(onRoleResult: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onRoleResult() }
    WarningBanner(
        title = stringResource(TelephonyR.string.dak_telephony_not_default_title),
        body = stringResource(TelephonyR.string.dak_telephony_not_default_body),
        actionLabel = stringResource(TelephonyR.string.dak_telephony_make_default),
        onAction = {
            val request = runCatching { DefaultSmsRole.requestIntent(context) }.getOrNull()
            val launched = request != null && runCatching { launcher.launch(request) }.isSuccess
            if (!launched) {
                // No role dialog on this device: the system's default-apps screen instead.
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        },
    )
}

@Composable
private fun CarrierNoticeText(notice: CarrierNotice, recipientLimit: Int?) {
    val text = when (notice) {
        CarrierNotice.GROUP_AS_INDIVIDUAL -> stringResource(TelephonyR.string.dak_telephony_group_mms_off)
        CarrierNotice.MMS_DISABLED -> stringResource(TelephonyR.string.dak_telephony_mms_disabled)
        CarrierNotice.TOO_MANY_RECIPIENTS -> stringResource(TelephonyR.string.dak_telephony_too_many_recipients, recipientLimit ?: 0)
        CarrierNotice.TEXT_TOO_LONG -> stringResource(TelephonyR.string.dak_telephony_mms_text_too_long)
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ComposerStatusRow(ui: ComposerUi, actions: ComposerActions) {
    val sim = ui.sim
    if (sim == null && !ui.showSegments && ui.normalizedHint == null) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sim != null) {
            SimChip(sim = sim, onClick = if (ui.canSwitchSim) actions::onSwitchSim else null)
            if (ui.roaming) {
                TokenChip(
                    label = stringResource(R.string.scr_composer_roaming, simLabel(sim)),
                    colors = DakTheme.colors.warning,
                )
            }
        }
        if (ui.showSegments && ui.segments.segments > 0) {
            Text(
                stringResource(R.string.scr_composer_segments, ui.segments.segments, ui.segments.remainingInSegment),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.normalizedHint?.let {
            Text(
                stringResource(R.string.scr_composer_sent_as, it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "SIM 2" style label for chips and hints. */
@Composable
fun simLabel(sim: SimInfo): String =
    if (sim.slotIndex >= 0) stringResource(R.string.sim_n, (sim.slotIndex + 1).toString()) else sim.displayName

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendButton(isMms: Boolean, enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container = if (enabled) scheme.primary else scheme.surfaceContainerHighest
    val content = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant
    val description = stringResource(if (isMms) R.string.scr_composer_send_mms else R.string.scr_composer_send)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(container)
            .combinedClickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = description, tint = content, modifier = Modifier.size(20.dp))
            if (isMms) Text(stringResource(R.string.scr_composer_mms_chip), style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

@Composable
private fun AttachmentStrip(attachments: List<ComposerAttachment>, onRemove: (ComposerAttachment) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (a in attachments) {
            Box {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    if (a.isImage) {
                        AsyncImage(
                            model = a.uri ?: a.bytes,
                            contentDescription = a.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(72.dp),
                        )
                    } else {
                        Column(Modifier.size(width = 120.dp, height = 72.dp).padding(8.dp), verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(a.name ?: a.mimeType, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // A 40dp button over the thumbnail's corner: big enough to hit, the icon stays small.
                IconButton(onClick = { onRemove(a) }, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.scr_composer_remove_attachment), modifier = Modifier.padding(4.dp).size(16.dp))
                    }
                }
            }
        }
    }
}

/** The attachment tray: every source is a system picker, so Dak needs no storage permission. */
@Composable
private fun AttachmentTray(onAttachment: (ComposerAttachment) -> Unit, onText: (String) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    fun addUri(uri: Uri, fallbackMime: String) {
        scope.launch {
            val (name, mime) = withContext(Dispatchers.IO) { describe(context, uri, fallbackMime) }
            onAttachment(ComposerAttachment(UUID.randomUUID().toString(), mime, name, uri = uri))
            onDone()
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) addUri(uri, "image/jpeg") }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) addUri(uri, "application/octet-stream") }
    val cameraPreview = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                }
                onAttachment(ComposerAttachment(UUID.randomUUID().toString(), "image/jpeg", "photo.jpg", bytes = bytes))
                onDone()
            }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCameraUri?.let(Uri::parse)
        pendingCameraUri = null
        if (saved && uri != null) {
            onAttachment(ComposerAttachment(UUID.randomUUID().toString(), "image/jpeg", "photo.jpg", uri = uri))
            onDone()
        }
    }
    val contact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) {
            scope.launch {
                val card = withContext(Dispatchers.IO) { readVCard(context, uri) }
                if (card != null) {
                    onAttachment(ComposerAttachment(UUID.randomUUID().toString(), "text/x-vcard", card.first, bytes = card.second))
                    onDone()
                } else {
                    Toast.makeText(context, R.string.scr_composer_contact_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val link = if (granted) lastKnownMapsLink(context) else null
        if (link != null) {
            onText(link)
            onDone()
        } else {
            Toast.makeText(context, R.string.scr_composer_location_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrayButton(Icons.Outlined.PhotoCamera, stringResource(R.string.scr_tray_camera)) {
            val uri = cameraOutputUri(context)
            // launch() throws ActivityNotFoundException on devices without a camera app (camera is optional).
            val launched = uri != null && runCatching {
                pendingCameraUri = uri.toString()
                camera.launch(uri)
            }.isSuccess
            if (!launched) {
                pendingCameraUri = null
                runCatching { cameraPreview.launch(null) }
            }
        }
        TrayButton(Icons.Outlined.Image, stringResource(R.string.scr_tray_gallery)) {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        TrayButton(Icons.Outlined.InsertDriveFile, stringResource(R.string.scr_tray_file)) { files.launch(arrayOf("*/*")) }
        TrayButton(Icons.Outlined.ContactPage, stringResource(R.string.scr_tray_contact)) { contact.launch(null) }
        TrayButton(Icons.Outlined.LocationOn, stringResource(R.string.scr_tray_location)) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val link = lastKnownMapsLink(context)
                if (link != null) { onText(link); onDone() } else Toast.makeText(context, R.string.scr_composer_location_unavailable, Toast.LENGTH_SHORT).show()
            } else {
                locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
    }
}

@Composable
private fun TrayButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun joinText(current: String, addition: String): String =
    if (current.isBlank()) addition else current.trimEnd() + " " + addition

private fun tomorrowAtNine(): Long = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, 1)
    set(Calendar.HOUR_OF_DAY, 9)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Content URI for the camera to write into, via the app's FileProvider; null when the provider is not declared. */
private fun cameraOutputUri(context: Context): Uri? = runCatching {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
    FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
}.getOrNull()

/** Authority suffix of the FileProvider the camera writes through (see app/README.md, "Screens"). */
const val FILE_PROVIDER_SUFFIX = ".dakfiles"

private fun describe(context: Context, uri: Uri, fallbackMime: String): Pair<String?, String> {
    val mime = context.contentResolver.getType(uri) ?: fallbackMime
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    return name to mime
}

/** (file name, vCard bytes) for a picked contact. */
private fun readVCard(context: Context, contactUri: Uri): Pair<String, ByteArray>? = runCatching {
    val projection = arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME)
    val (lookup, name) = context.contentResolver.query(contactUri, projection, null, null, null)?.use { c ->
        if (c.moveToFirst()) (c.getString(0) to (c.getString(1) ?: "contact")) else null
    } ?: return@runCatching null
    if (lookup == null) return@runCatching null
    val vcardUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookup)
    val bytes = context.contentResolver.openInputStream(vcardUri)?.use { it.readBytes() } ?: return@runCatching null
    val safe = name.filter { it.isLetterOrDigit() || it == ' ' }.trim().ifBlank { "contact" }
    "$safe.vcf" to bytes
}.getOrNull()

/** A maps link for the freshest last-known location, or null. Requires a location permission. */
private fun lastKnownMapsLink(context: Context): String? {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val best: Location? = try {
        manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    } catch (e: SecurityException) {
        null
    }
    return best?.let { String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", it.latitude, it.longitude) }
}
