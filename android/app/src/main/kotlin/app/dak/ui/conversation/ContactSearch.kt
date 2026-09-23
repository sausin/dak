package app.dak.ui.conversation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A contact phone number offered in the recipient picker. */
data class ContactSuggestion(val name: String, val number: String, val photoUri: String?, val type: String?)

/** Recipient-picker search over the phone's contacts (name or number); empty without READ_CONTACTS. */
@Singleton
class ContactSearch @Inject constructor(@ApplicationContext private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    suspend fun search(query: String, limit: Int = 25): List<ContactSuggestion> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty() || !hasPermission()) return@withContext emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q))
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        val out = ArrayList<ContactSuggestion>()
        val seen = HashSet<String>()
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val number = c.getString(1) ?: continue
                    val digits = number.filter { it.isDigit() || it == '+' }
                    if (!seen.add(digits)) continue
                    val typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4))?.toString()
                    out += ContactSuggestion(c.getString(0) ?: number, number, c.getString(2), typeLabel)
                }
            }
        } catch (e: SecurityException) {
            return@withContext emptyList()
        } catch (e: IllegalArgumentException) {
            return@withContext emptyList()
        }
        out
    }
}
