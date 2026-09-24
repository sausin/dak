package app.dak.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.LruCache
import androidx.core.content.ContextCompat
import app.dak.index.ContactLookup
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Collator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A saved contact matched to a phone number. */
data class ContactMatch(val displayName: String, val photoUri: String?, val lookupKey: String?)

/**
 * Contacts access for the whole app and for :core-index ([ContactLookup]): cached phone-number → contact lookup
 * via `PhoneLookup` (handles formatting and country-code differences) plus name searches for `from:` queries.
 * Everything returns empty results without READ_CONTACTS. Call off the main thread.
 */
@Singleton
class AndroidContactLookup @Inject constructor(@ApplicationContext private val context: Context) : ContactLookup {

    private val cache = LruCache<String, Result>(512)

    private sealed interface Result {
        data class Found(val match: ContactMatch) : Result
        data object None : Result
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Contact for [address], or null (no permission, alphanumeric sender, or not saved). */
    fun find(address: String): ContactMatch? {
        if (address.isBlank() || address.none { it.isDigit() } || !hasPermission()) return null
        cache.get(address)?.let { return (it as? Result.Found)?.match }
        val result = query(address)
        cache.put(address, result)
        return (result as? Result.Found)?.match
    }

    override fun isContact(address: String): Boolean = find(address) != null

    override fun displayName(address: String): String? = find(address)?.displayName

    override fun addressesMatching(nameQuery: String): List<String> {
        val q = nameQuery.trim()
        if (q.isEmpty() || !hasPermission()) return emptyList()
        return queryPhones(
            projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            nameLike = "%" + escapeLike(q) + "%",
            limit = 200,
        ) { it.getString(0) }.filterNotNull().distinct()
    }

    override fun namesMatching(prefix: String, limit: Int): List<String> {
        val q = prefix.trim()
        if (q.isEmpty() || limit <= 0 || !hasPermission()) return emptyList()
        val names = queryPhones(
            projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            nameLike = "%" + escapeLike(q) + "%",
            limit = limit * 4,
        ) { it.getString(0) }.filterNotNull().distinct()
        // Prefix matches first, then word-start and substring matches.
        // Prefix matches first, then in the app language's alphabetical order (Collator, not code units).
        val collator = Collator.getInstance(context.resources.configuration.locales[0] ?: Locale.getDefault())
            .apply { strength = Collator.SECONDARY }
        return names.sortedWith(compareBy<String> { !it.startsWith(q, ignoreCase = true) }.then(collator)).take(limit)
    }

    private fun <T> queryPhones(projection: Array<String>, nameLike: String, limit: Int, read: (android.database.Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
                arrayOf(nameLike),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) out += read(c)
            }
        } catch (e: SecurityException) {
            return emptyList()
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }
        return out
    }

    private fun escapeLike(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Drops the cached answer for [address] (after the user saved or edited that contact). */
    fun forget(address: String) {
        cache.remove(address)
    }

    /** Drop cached answers (e.g. after the contacts permission is granted). */
    fun invalidate() = cache.evictAll()

    private fun query(address: String): Result {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        val projection = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
            ContactsContract.PhoneLookup.LOOKUP_KEY,
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (name.isNullOrBlank()) Result.None else Result.Found(ContactMatch(name, c.getString(1), c.getString(2)))
                } else Result.None
            } ?: Result.None
        } catch (e: SecurityException) {
            Result.None
        } catch (e: IllegalArgumentException) {
            Result.None
        }
    }
}
