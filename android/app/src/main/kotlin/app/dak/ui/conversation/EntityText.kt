package app.dak.ui.conversation

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import app.dak.classify.LinkExtractor
import app.dak.classify.entities.EntityExtractor
import app.dak.classify.entities.EntityHint
import app.dak.classify.entities.EntitySpan
import app.dak.classify.entities.EntityType
import app.dak.finance.money.CurrencyTable
import app.dak.finance.money.MoneyParser
import app.dak.index.MessageItem
import app.dak.index.repo.LedgerRepository
import app.dak.search.AmountTokens
import app.dak.telephony.region.RegionProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.RoundingMode

/** Hilt dependencies of entity rendering (a bubble is not a ViewModel owner). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface EntityDeps {
    fun regionProvider(): RegionProvider
    fun ledgerRepository(): LedgerRepository
}

internal fun entityDeps(context: Context): EntityDeps =
    EntryPointAccessors.fromApplication(context.applicationContext, EntityDeps::class.java)

/**
 * A message body with smart entities: phone numbers, amounts, account masks, UPI ids, reference / PNR / tracking
 * numbers and emails get a subtle underline (not a blue link) and open a type-specific action sheet on tap
 * ([EntityActionSheet]). OTP highlight/copy and URL links keep coming from [base] (see [annotateMessage]); entity
 * spans are detected off the main thread and overlaid once ready, so the first frame never waits on them.
 *
 * @param base the already-annotated body (OTP highlight, search highlights, safety-checked links).
 * @param onNavigate opens a route (compose, search, passbook) from an entity action.
 */
@Composable
fun EntityMessageText(
    item: MessageItem,
    base: AnnotatedString,
    style: TextStyle,
    color: Color,
    onNavigate: (String) -> Unit,
    onLink: (app.dak.classify.ExtractedLink) -> Unit,
) {
    val context = LocalContext.current
    val spans by produceState(initialValue = emptyList<EntitySpan>(), item.body, item.subId, item.otp?.code) {
        value = withContext(Dispatchers.Default) { detectEntities(context, item) }
    }
    var selected by rememberSaveable(item.key.toString()) { mutableStateOf<Int?>(null) }
    val underline = color.copy(alpha = 0.10f)
    val text = remember(base, spans, underline) {
        if (spans.isEmpty()) base else overlayEntities(base, item, spans, underline) { index -> selected = index }
    }
    Text(text, style = style, color = color)

    val index = selected
    val span = index?.let { spans.getOrNull(it) }
    if (span != null) {
        EntityActionSheet(
            item = item,
            span = span,
            onNavigate = onNavigate,
            onOpenLink = { raw -> LinkExtractor.extract(raw).firstOrNull()?.let(onLink) },
            onDismiss = { selected = null },
        )
    }
}

/** Spans [base] does not already make tappable (OTP code, URLs), each an underlined clickable. */
private fun overlayEntities(
    base: AnnotatedString,
    item: MessageItem,
    spans: List<EntitySpan>,
    tint: Color,
    onTap: (Int) -> Unit,
): AnnotatedString {
    val body = item.body
    // Ranges annotateMessage already linked: the first occurrence of the OTP code, and every extracted URL.
    val taken = ArrayList<IntRange>()
    item.otp?.code?.let { code -> body.indexOf(code).takeIf { it >= 0 }?.let { taken += it until it + code.length } }
    var from = 0
    for (link in LinkExtractor.extract(body)) {
        val at = body.indexOf(link.raw, from)
        if (at < 0) continue
        taken += at until at + link.raw.length
        from = at + link.raw.length
    }
    return buildAnnotatedString {
        append(base)
        spans.forEachIndexed { index, span ->
            if (span.type == EntityType.OTP || span.type == EntityType.URL) return@forEachIndexed
            if (span.end > body.length || taken.any { it.first < span.end && span.start <= it.last }) return@forEachIndexed
            addLink(
                LinkAnnotation.Clickable(
                    tag = "entity:${span.type.name}:$index",
                    styles = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline, background = tint)),
                    linkInteractionListener = { onTap(index) },
                ),
                span.start,
                span.end,
            )
        }
    }
}

/** Runs [EntityExtractor] for [item] with `:finance` amounts as hints and the SIM's region for phone numbers. */
private fun detectEntities(context: Context, item: MessageItem): List<EntitySpan> = try {
    val region = runCatching { entityDeps(context).regionProvider().forSubId(item.subId) }.getOrNull()
    val symbols = CurrencyTable.symbolMapFor(region?.homeCurrency)
    val amounts = MoneyParser.findAll(item.body.take(EntityExtractor.MAX_CHARS), symbols).mapNotNull { occ ->
        val hundredths = runCatching {
            occ.money.toBigDecimal().setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        }.getOrNull() ?: return@mapNotNull null
        EntityHint(EntityType.AMOUNT, occ.range.first, occ.range.last + 1, AmountTokens.queryText(hundredths))
    }
    EntityExtractor.extract(item.body, region?.countryIso, amounts, item.otp?.code)
} catch (e: RuntimeException) {
    emptyList() // entity detection is a convenience; the plain body always renders
}
