package app.dak.classify.unicode

import java.text.Normalizer

/**
 * UTS #39 §4 confusable detection: [skeleton] maps a string to its prototype so that two strings that look alike get
 * the same skeleton (`hdfcbаnk` with Cyrillic `а`, `hdfcbank`, `ｈｄｆｃｂａｎｋ` and `𝐡𝐝𝐟𝐜𝐛𝐚𝐧𝐤` all become `hdfcbank`'s
 * skeleton). The mapping is generated from Unicode's `confusables.txt` (MA table) for Latin, Cyrillic, Greek,
 * Armenian, Cherokee, Devanagari, Bengali and Common/Inherited characters; see `confusables.txt`'s header in the
 * resources for the version. Characters of other scripts map to themselves.
 *
 * Skeletons are only ever compared with other skeletons (UTS #39 maps `m` to `rn`, `1` and `I` to `l`, `0` to `O`), so
 * callers keep skeletons of both sides.
 */
public object Confusables {

    /**
     * UTS #39 skeleton: decompose, drop default-ignorable characters (zero-width, bidi controls, variation selectors),
     * map every code point through the confusables table, NFD again. The first step is NFKD rather than UTS #39's NFD:
     * the confusables data assumes identifiers already in NFKC, and sender names and hosts reach here unnormalised
     * (fullwidth `ｈｄｆｃ`, mathematical `𝐡𝐝𝐟𝐜`). Linear in the input.
     */
    public fun skeleton(s: String): String {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFKD)
        val table = UnicodeTables.confusables
        val sb = StringBuilder(nfd.length)
        var i = 0
        while (i < nfd.length) {
            val cp = nfd.codePointAt(i)
            i += Character.charCount(cp)
            if (isDefaultIgnorable(cp)) continue
            val mapped = table[cp]
            if (mapped != null) sb.append(mapped) else sb.appendCodePoint(cp)
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFD)
    }

    /**
     * [skeleton] made case-insensitive (skeleton of the lower-cased skeleton): `HDFC০` (Bengali zero, whose prototype
     * is `o`) matches `HDFCO`. For sender names, which are compared regardless of case; not part of UTS #39.
     */
    public fun caseFoldedSkeleton(s: String): String = skeleton(skeleton(s).lowercase())

    /** True when [a] and [b] are confusable (same skeleton). */
    public fun areConfusable(a: String, b: String): Boolean = skeleton(a) == skeleton(b)

    /**
     * [skeleton] with combining marks removed as well: `hdfcbạnk` and `hdfcbänk` fold to `hdfcbank`'s. Not part of
     * UTS #39; used only to find which brand a look-alike imitates, never to declare two strings equal.
     */
    public fun looseSkeleton(s: String): String {
        val skeleton = skeleton(s)
        if (skeleton.none { it.code >= 0x300 }) return skeleton
        val sb = StringBuilder(skeleton.length)
        var i = 0
        while (i < skeleton.length) {
            val cp = skeleton.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) continue
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    /** Characters that the skeletons of ASCII letters, digits and `-` are made of. */
    private val latinSkeletonChars: Set<Int> by lazy {
        val out = HashSet<Int>()
        for (c in ('a'..'z') + ('A'..'Z') + ('0'..'9') + '-') skeleton(c.toString()).codePoints().forEach { out += it }
        out
    }

    /**
     * True when [s] contains characters outside ASCII but its skeleton is spelled only with what ASCII letters, digits
     * and hyphens look like: it can pass for an ASCII string (`НDFCBK`, `раураl`, `ＳＢＩ`, `HDFC০`). Separators
     * (spaces, dots, other punctuation) are ignored.
     */
    public fun isAsciiLookalike(s: String): Boolean {
        if (s.all { it.code < 0x80 }) return false
        var sawLetterOrDigit = false
        val skeleton = skeleton(s)
        var i = 0
        while (i < skeleton.length) {
            val cp = skeleton.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetterOrDigit(cp) && cp != '-'.code) {
                if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) return false
                continue
            }
            if (cp !in latinSkeletonChars) return false
            sawLetterOrDigit = true
        }
        return sawLetterOrDigit
    }

    /**
     * Default_Ignorable_Code_Point characters that show up in spoofed text: soft hyphen, combining grapheme joiner,
     * Arabic letter mark, Hangul fillers, zero-width and bidi format characters, word joiner and invisible operators,
     * variation selectors, BOM, and the tag and variation-selector supplement blocks.
     */
    internal fun isDefaultIgnorable(cp: Int): Boolean =
        cp == 0x00AD || cp == 0x034F || cp == 0x061C || cp == 0x115F || cp == 0x1160 || cp in 0x17B4..0x17B5 ||
            cp in 0x180B..0x180F || cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2060..0x206F ||
            cp == 0x3164 || cp in 0xFE00..0xFE0F || cp == 0xFEFF || cp == 0xFFA0 || cp in 0xFFF0..0xFFF8 ||
            cp in 0x1BCA0..0x1BCA3 || cp in 0x1D173..0x1D17A || cp in 0xE0000..0xE0FFF
}
