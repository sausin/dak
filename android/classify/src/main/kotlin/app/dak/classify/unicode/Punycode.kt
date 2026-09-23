package app.dak.classify.unicode

/**
 * Punycode (RFC 3492): the Bootstring encoding IDNA uses for the `xn--` labels. Pure Kotlin, so `:classify` stays a
 * plain JVM module (no ICU4J). Works on code points, not UTF-16 units; both directions return null instead of throwing
 * on bad input (non-basic characters in the basic part, invalid digits, overflow, unpaired surrogates), and both are
 * linear in the output times the number of distinct code points, bounded by [MAX_INPUT] characters.
 */
public object Punycode {
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 0x80
    private const val DELIMITER = '-'

    /** Longest input accepted (a DNS label is at most 63 octets; this leaves room for whole-host use in tests). */
    public const val MAX_INPUT: Int = 1_024

    /** Encodes [input] (one label, without `xn--`). Null for unpaired surrogates or oversized input. */
    public fun encode(input: String): String? {
        if (input.length > MAX_INPUT) return null
        val cps = codePoints(input) ?: return null
        val out = StringBuilder(input.length + 8)
        for (cp in cps) if (cp < 0x80) out.append(cp.toChar())
        val basicCount = out.length
        var handled = basicCount
        if (basicCount > 0) out.append(DELIMITER)
        var n = INITIAL_N
        var delta = 0L
        var bias = INITIAL_BIAS
        while (handled < cps.size) {
            var m = Int.MAX_VALUE
            for (cp in cps) if (cp >= n && cp < m) m = cp
            delta += (m - n).toLong() * (handled + 1)
            if (delta > Int.MAX_VALUE) return null
            n = m
            for (cp in cps) {
                if (cp < n) {
                    delta++
                    if (delta > Int.MAX_VALUE) return null
                }
                if (cp == n) {
                    var q = delta.toInt()
                    var k = BASE
                    while (true) {
                        val t = threshold(k, bias)
                        if (q < t) break
                        out.append(digit(t + (q - t) % (BASE - t)))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    out.append(digit(q))
                    bias = adapt(delta.toInt(), handled + 1, handled == basicCount)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return out.toString()
    }

    /** Decodes [input] (one label, without `xn--`). Null when it is not valid Punycode. */
    public fun decode(input: String): String? {
        if (input.length > MAX_INPUT) return null
        val output = ArrayList<Int>(input.length)
        val lastDelimiter = input.lastIndexOf(DELIMITER)
        val basicEnd = if (lastDelimiter < 0) 0 else lastDelimiter
        for (i in 0 until basicEnd) {
            val c = input[i]
            if (c.code >= 0x80) return null
            output += c.code
        }
        var n = INITIAL_N
        var i = 0L
        var bias = INITIAL_BIAS
        var pos = if (lastDelimiter < 0) 0 else lastDelimiter + 1
        while (pos < input.length) {
            val oldI = i
            var w = 1L
            var k = BASE
            while (true) {
                if (pos >= input.length) return null
                val d = digitValue(input[pos++])
                if (d < 0) return null
                i += d * w
                if (i > Int.MAX_VALUE) return null
                val t = threshold(k, bias)
                if (d < t) break
                w *= (BASE - t)
                if (w > Int.MAX_VALUE) return null
                k += BASE
            }
            val length = output.size + 1
            bias = adapt((i - oldI).toInt(), length, oldI == 0L)
            n += (i / length).toInt()
            if (n > Character.MAX_CODE_POINT || n < 0) return null
            i %= length
            if (n in 0xD800..0xDFFF) return null
            output.add(i.toInt(), n)
            i++
        }
        return buildString(output.size) { for (cp in output) appendCodePoint(cp) }
    }

    private fun threshold(k: Int, bias: Int): Int = when {
        k <= bias -> TMIN
        k >= bias + TMAX -> TMAX
        else -> k - bias
    }

    private fun adapt(deltaIn: Int, numPoints: Int, firstTime: Boolean): Int {
        var delta = if (firstTime) deltaIn / DAMP else deltaIn / 2
        delta += delta / numPoints
        var k = 0
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= BASE - TMIN
            k += BASE
        }
        return k + (BASE - TMIN + 1) * delta / (delta + SKEW)
    }

    private fun digit(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)

    private fun digitValue(c: Char): Int = when (c) {
        in 'a'..'z' -> c - 'a'
        in 'A'..'Z' -> c - 'A'
        in '0'..'9' -> c - '0' + 26
        else -> -1
    }

    private fun codePoints(s: String): IntArray? {
        val out = IntArray(s.length)
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return null
                out[n++] = Character.toCodePoint(c, s[i + 1])
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) return null
                out[n++] = c.code
                i++
            }
        }
        return out.copyOf(n)
    }
}
