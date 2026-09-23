package app.dak.birthdays

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.util.Log
import androidx.core.content.ContextCompat
import app.dak.automations.birthdays.BirthdayDates
import app.dak.automations.birthdays.ContactDate
import app.dak.automations.birthdays.OccasionKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One phone number of a contact, with its localized type label ("Mobile", "Work", …). */
data class ContactNumber(val number: String, val label: String?, val isPrimary: Boolean)

/** A birthday or anniversary found in the user's contacts. */
data class ContactOccasion(
    val contactId: Long,
    val lookupKey: String?,
    val name: String,
    val firstName: String?,
    val photoUri: String?,
    val kind: OccasionKind,
    val date: ContactDate,
    val numbers: List<ContactNumber>,
)

/**
 * Reads `ContactsContract.CommonDataKinds.Event` birthdays (and optionally anniversaries) with READ_CONTACTS, plus
 * the contacts' phone numbers and given names. Read on demand only (screen open, daily housekeeping): Dak registers
 * no contacts ContentObserver, to save battery. Returns an empty list without the permission; never throws.
 */
@Singleton
class ContactOccasionReader @Inject constructor(@ApplicationContext private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    suspend fun read(includeAnniversaries: Boolean): List<ContactOccasion> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        try {
            val events = readEvents(includeAnniversaries)
            if (events.isEmpty()) return@withContext emptyList()
            val ids = events.map { it.contactId }.distinct()
            val numbers = readNumbers(ids)
            val givenNames = readGivenNames(ids)
            events.map { e ->
                ContactOccasion(
                    contactId = e.contactId,
                    lookupKey = e.lookupKey,
                    name = e.name,
                    firstName = givenNames[e.contactId],
                    photoUri = e.photoUri,
                    kind = e.kind,
                    date = e.date,
                    numbers = numbers[e.contactId].orEmpty(),
                )
            }
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not read contact events", e)
            emptyList()
        }
    }

    private class RawEvent(
        val contactId: Long,
        val lookupKey: String?,
        val name: String,
        val photoUri: String?,
        val kind: OccasionKind,
        val date: ContactDate,
    )

    private fun readEvents(includeAnniversaries: Boolean): List<RawEvent> {
        val types = if (includeAnniversaries) "${Event.TYPE_BIRTHDAY},${Event.TYPE_ANNIVERSARY}" else "${Event.TYPE_BIRTHDAY}"
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.LOOKUP_KEY,
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.Data.PHOTO_THUMBNAIL_URI,
            Event.START_DATE,
            Event.TYPE,
        )
        val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${Event.TYPE} IN ($types)"
        val out = LinkedHashMap<String, RawEvent>()
        context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, arrayOf(Event.CONTENT_ITEM_TYPE), null)
            ?.use { c ->
                while (c.moveToNext()) {
                    val contactId = c.getLong(0)
                    val date = BirthdayDates.parse(c.getString(4)) ?: continue
                    val kind = if (c.getInt(5) == Event.TYPE_ANNIVERSARY) OccasionKind.ANNIVERSARY else OccasionKind.BIRTHDAY
                    val key = "$contactId:${kind.name}"
                    // Several raw contacts (Google, WhatsApp, SIM) may carry the same event; prefer one with a year.
                    val existing = out[key]
                    if (existing != null && (existing.date.year != null || date.year == null)) continue
                    out[key] = RawEvent(
                        contactId = contactId,
                        lookupKey = c.getString(1),
                        name = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue,
                        photoUri = c.getString(3),
                        kind = kind,
                        date = date,
                    )
                }
            }
        return out.values.toList()
    }

    private fun readNumbers(ids: List<Long>): Map<Long, List<ContactNumber>> {
        val result = HashMap<Long, MutableList<ContactNumber>>()
        val projection = arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.IS_SUPER_PRIMARY)
        for (chunk in ids.chunked(CHUNK)) {
            val selection = "${Phone.CONTACT_ID} IN (${chunk.joinToString(",")})"
            context.contentResolver.query(Phone.CONTENT_URI, projection, selection, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val list = result.getOrPut(c.getLong(0)) { mutableListOf() }
                    val digits = number.filter { it.isDigit() }.takeLast(10)
                    if (list.any { it.number.filter { ch -> ch.isDigit() }.takeLast(10) == digits }) continue
                    val label = Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3))?.toString()
                    list += ContactNumber(number, label, c.getInt(4) != 0)
                }
            }
        }
        return result.mapValues { (_, v) -> v.sortedByDescending { it.isPrimary } }
    }

    private fun readGivenNames(ids: List<Long>): Map<Long, String> {
        val result = HashMap<Long, String>()
        val projection = arrayOf(ContactsContract.Data.CONTACT_ID, StructuredName.GIVEN_NAME)
        for (chunk in ids.chunked(CHUNK)) {
            val selection = "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.CONTACT_ID} IN (${chunk.joinToString(",")})"
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, arrayOf(StructuredName.CONTENT_ITEM_TYPE), null)
                ?.use { c ->
                    while (c.moveToNext()) {
                        val given = c.getString(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                        result.putIfAbsent(c.getLong(0), given)
                    }
                }
        }
        return result
    }

    private companion object {
        const val TAG = "DakBirthdays"
        const val CHUNK = 400
    }
}
