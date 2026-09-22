package app.dak.ui.conversation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import app.dak.classify.ExtractedLink
import app.dak.classify.LinkExtractor

/** Colours used when annotating a message body; always theme tokens passed in by the caller. */
data class MessageTextColors(
    val otpBackground: Color,
    val otpContent: Color,
    val highlightBackground: Color,
    val link: Color,
)

/**
 * Builds a message body with: the OTP code highlighted and tappable (copy), search-match ranges highlighted, and
 * every URL as a tappable link that goes through the link-safety check first ([onLink]).
 *
 * @param highlights inclusive char ranges into [body] (as `SearchHit.highlights` provides them).
 */
fun annotateMessage(
    body: String,
    colors: MessageTextColors,
    otpCode: String? = null,
    highlights: List<IntRange> = emptyList(),
    onOtp: ((String) -> Unit)? = null,
    onLink: ((ExtractedLink) -> Unit)? = null,
): AnnotatedString = buildAnnotatedString {
    append(body)
    for (range in highlights) {
        val start = range.first.coerceIn(0, body.length)
        val end = (range.last + 1).coerceIn(start, body.length)
        if (end > start) addStyle(SpanStyle(background = colors.highlightBackground, fontWeight = FontWeight.SemiBold), start, end)
    }
    if (otpCode != null) {
        val at = body.indexOf(otpCode)
        if (at >= 0) {
            val end = at + otpCode.length
            addStyle(SpanStyle(background = colors.otpBackground, color = colors.otpContent, fontWeight = FontWeight.Bold), at, end)
            if (onOtp != null) {
                addLink(LinkAnnotation.Clickable(tag = "otp:$otpCode", linkInteractionListener = { onOtp(otpCode) }), at, end)
            }
        }
    }
    if (onLink != null) {
        var from = 0
        for (link in LinkExtractor.extract(body)) {
            val at = body.indexOf(link.raw, from)
            if (at < 0) continue
            val end = at + link.raw.length
            from = end
            addLink(
                LinkAnnotation.Clickable(
                    tag = "url:${link.raw}",
                    styles = TextLinkStyles(style = SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { onLink(link) },
                ),
                at,
                end,
            )
        }
    }
}
