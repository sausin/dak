package app.dak.ui.common.text

import android.text.BidiFormatter
import android.text.TextDirectionHeuristics

/**
 * Makes untrusted text (a sender display name/address, a message snippet) safe to splice into
 * surrounding UI text, regardless of what script or bidi control characters it contains.
 *
 * Two independent problems are handled:
 * - **Directionality leakage**: an RTL sender name (Arabic/Urdu/Hebrew) sitting next to LTR UI
 *   text (a timestamp, a balance) can visually reorder that neighbouring text unless it is
 *   isolated. [isolate] wraps it in Unicode isolate marks (FSI...PDI) so its direction never
 *   escapes its own span.
 * - **Bidi spoofing**: a sender can put explicit bidi override/control characters (e.g. U+202E
 *   RIGHT-TO-LEFT OVERRIDE) or stray zero-width characters in a display name to visually disguise
 *   it (classic "right-to-left override" filename/sender spoof). [sanitizeDisplayName] strips
 *   those, while keeping ZWJ/ZWNJ where they sit between two letters, since that is how Indic and
 *   Arabic scripts render some letter conjuncts correctly.
 */
object BidiText {

    private val formatter: BidiFormatter = BidiFormatter.getInstance()

    // Explicit bidi control characters: LRE/RLE/PDF/LRO/RLO, the isolates themselves (in case they
    // arrived pre-embedded, which would just make wrapping again redundant/harmless - stripped for
    // safety), and LRM/RLM. These have no legitimate place in a sender name or SMS snippet.
    private val bidiControlChars = charArrayOf(
        '‪', '‫', '‬', '‭', '‮',
        '⁦', '⁧', '⁨', '⁩',
        '‎', '‏',
    )

    // Zero-width characters with no legitimate role in a display name (invisible spacer / BOM).
    // ZWJ (U+200D) and ZWNJ (U+200C) are handled separately since they are sometimes legitimate.
    private val strippedZeroWidth = charArrayOf('​', '⁠', '﻿')

    /**
     * Wraps [text] in Unicode isolate marks (FSI...PDI) so its own bidi directionality can never
     * affect the text around it once composed into a larger string or [androidx.compose.ui.text.AnnotatedString].
     * [isRtlContext] should reflect the layout direction of the surrounding UI (e.g.
     * `LocalLayoutDirection.current`).
     */
    fun isolate(text: String, isRtlContext: Boolean = false): String {
        val heuristic = if (isRtlContext) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR
        return formatter.unicodeWrap(text, heuristic)
    }

    /**
     * Strips characters that have no business in a display name: explicit bidi
     * override/embedding/isolate/mark characters (the U+202E spoofing trick and friends) and
     * stray zero-width characters, while preserving ZWJ/ZWNJ when they sit directly between two
     * letters, where they are a legitimate part of how an Indic or Arabic conjunct renders.
     */
    fun sanitizeDisplayName(name: String): String {
        if (name.none { isSuspect(it) }) return name
        val sb = StringBuilder(name.length)
        for (i in name.indices) {
            val c = name[i]
            when {
                c in bidiControlChars -> continue
                c in strippedZeroWidth -> continue
                c == '‌' || c == '‍' -> {
                    val prevLetter = i > 0 && Character.isLetter(name[i - 1])
                    val nextLetter = i < name.length - 1 && Character.isLetter(name[i + 1])
                    if (prevLetter && nextLetter) sb.append(c)
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Sanitizes then isolates [name] - the one call sites rendering an untrusted name should use. */
    fun displaySafe(name: String, isRtlContext: Boolean = false): String =
        isolate(sanitizeDisplayName(name), isRtlContext)

    private fun isSuspect(c: Char): Boolean =
        c in bidiControlChars || c in strippedZeroWidth || c == '‌' || c == '‍'
}
