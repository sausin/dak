package app.dak.classify

import java.security.MessageDigest
import java.util.Base64

/**
 * Computes the 11-character SMS Retriever API app hash, using exactly Google's published
 * `AppSignatureHelper` algorithm:
 *
 * 1. `appInfo = "$packageName $hexSignature"`, where `hexSignature` is the app's signing
 *    certificate rendered the way `android.content.pm.Signature.toCharsString()` does (lowercase
 *    hex of the raw certificate bytes).
 * 2. `hash = SHA-256(UTF-8 bytes of appInfo)`.
 * 3. Take the first 9 bytes of `hash`.
 * 4. Base64-encode with no padding and no line wrapping.
 * 5. Take the first 11 characters.
 */
public object AppSignatureHash {

    private const val HASH_LENGTH_BYTES = 9
    public const val HASH_LENGTH_CHARS: Int = 11

    /** Computes the hash from the raw DER-encoded certificate bytes of the app's signing signature. */
    public fun compute(packageName: String, signatureBytes: ByteArray): String =
        compute(packageName, signatureBytes.toHexCharsString())

    /**
     * Computes the hash directly from the hex string as `Signature.toCharsString()` would produce
     * it (lowercase hex, no separators, no `0x` prefix).
     */
    public fun compute(packageName: String, hexSignature: String): String {
        val appInfo = "$packageName $hexSignature"
        val digest = MessageDigest.getInstance("SHA-256").digest(appInfo.toByteArray(Charsets.UTF_8))
        val truncated = digest.copyOf(HASH_LENGTH_BYTES)
        val base64 = Base64.getEncoder().withoutPadding().encodeToString(truncated)
        return base64.substring(0, HASH_LENGTH_CHARS.coerceAtMost(base64.length))
    }

    private fun ByteArray.toHexCharsString(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(HEX_DIGITS[(b.toInt() shr 4) and 0xF])
            sb.append(HEX_DIGITS[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}

/**
 * Matches a consumed OTP (one an app or browser has already auto-read) to the package that
 * consumed it.
 *
 * @param hashesByPackage every installed app's SMS Retriever hash, keyed by package name (as
 *   computed by [AppSignatureHash]).
 * @param browserPackages package names of installed browsers, used for WebOTP matches (WebOTP has
 *   no per-app hash; any installed browser could have consumed it, so the first one is reported).
 */
public class ConsumedOtpMatcher(
    private val hashesByPackage: Map<String, String>,
    private val browserPackages: Set<String>,
) {
    private val packagesByHash: Map<String, String> = hashesByPackage.entries.associate { (pkg, hash) -> hash to pkg }

    /**
     * Returns the package name that most likely consumed [otp], or null if none can be determined.
     * A retriever-hash match is checked first; a WebOTP domain falls back to the first installed
     * browser (sorted for determinism), since WebOTP does not identify a package.
     */
    public fun consumerOf(otp: app.dak.core.model.OtpInfo): String? {
        otp.retrieverHash?.let { hash -> packagesByHash[hash]?.let { return it } }
        if (otp.webOtpDomain != null && browserPackages.isNotEmpty()) {
            return browserPackages.sorted().first()
        }
        return null
    }
}
