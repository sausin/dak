package app.dak.automation

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.PhoneLookup
import android.provider.Telephony
import androidx.core.content.ContextCompat
import app.dak.automations.forwarding.ForwardingRecipient
import app.dak.automations.forwarding.RecipientFacts
import app.dak.automations.safety.Addresses
import app.dak.automations.safety.RecipientSnapshot
import app.dak.automations.safety.shouldPauseForContact
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Result of checking a forwarding recipient against the phone's contacts right before a forward. */
enum class RecipientContactCheck {
    OK,
    /** The contact was deleted, or no longer lists the number. */
    NOT_A_CONTACT,
    /** READ_CONTACTS was revoked, so the recipient cannot be verified. */
    NO_ACCESS,
}

/**
 * Contacts side of auto-forwarding: reads the phone row the system contact picker returned, gathers the facts behind
 * the risky-recipient warning ([RecipientFacts]) and, before every forward, checks the recipient is still a contact.
 * Queries run directly (not through the cached [app.dak.di.AndroidContactLookup]) so a just-deleted contact is seen.
 * All calls hop to the IO dispatcher.
 */
@Singleton
class ForwardingContacts @Inject constructor(@ApplicationContext private val context: Context) {

    fun canReadContacts(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Number, name and lookup key of the phone row the system contact picker returned, or null. */
    suspend fun readPicked(uri: Uri): ForwardingRecipient? = withContext(Dispatchers.IO) {
        guarded {
            context.contentResolver.query(uri, arrayOf(Phone.NUMBER, Phone.DISPLAY_NAME, Phone.LOOKUP_KEY), null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val number = c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return@use null
                ForwardingRecipient(number, c.getString(1), c.getString(2)?.takeIf { it.isNotBlank() })
            }
        }
    }

    /**
     * What the risk heuristic needs about [recipient]: when its contact was last changed and whether any SMS was ever
     * exchanged with its number. Unknowns stay null.
     */
    suspend fun factsFor(recipient: ForwardingRecipient, homeCountryIso: String?): RecipientFacts = withContext(Dispatchers.IO) {
        RecipientFacts(
            number = recipient.number,
            contactLastUpdatedMillis = recipient.contactKey?.let { key -> contactUri(key)?.let { lastUpdated(it) } },
            hasMessageHistory = hasSmsHistory(recipient.number),
            homeCountryIso = homeCountryIso,
        )
    }

    /**
     * Is [recipient] still in the phone's contacts? With a lookup key (picked from contacts): the contact must still
     * exist and still list the number ([shouldPauseForContact]). Without one (rules saved before picking was required):
     * any contact must have the number.
     */
    suspend fun check(ruleId: String, recipient: ForwardingRecipient): RecipientContactCheck = withContext(Dispatchers.IO) {
        if (!canReadContacts()) return@withContext RecipientContactCheck.NO_ACCESS
        val key = recipient.contactKey
        val ok = if (key != null) {
            val numbers = contactUri(key)?.let { numbersOf(ContentUris.parseId(it)) }
            !shouldPauseForContact(RecipientSnapshot(ruleId, key, recipient.number), numbers)
        } else {
            isAnyContact(recipient.number)
        }
        if (ok) RecipientContactCheck.OK else RecipientContactCheck.NOT_A_CONTACT
    }

    /** The contact's current URI for [lookupKey] (follows re-aggregation), or null when it no longer exists. */
    private fun contactUri(lookupKey: String): Uri? = guarded {
        Contacts.lookupContact(context.contentResolver, Contacts.CONTENT_LOOKUP_URI.buildUpon().appendPath(lookupKey).build())
    }

    private fun lastUpdated(contactUri: Uri): Long? = guarded {
        context.contentResolver.query(contactUri, arrayOf(Contacts.CONTACT_LAST_UPDATED_TIMESTAMP), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }

    private fun numbersOf(contactId: Long): List<String>? = guarded {
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.NUMBER),
            "${Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            val numbers = ArrayList<String>()
            while (c.moveToNext()) c.getString(0)?.let { numbers += it }
            numbers
        }
    }

    private fun isAnyContact(number: String): Boolean {
        if (number.none { it.isDigit() }) return false
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return guarded {
            context.contentResolver.query(uri, arrayOf(PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() }
        } ?: false
    }

    /**
     * True when the SMS provider has any message to or from [number] (matched on its last 10 digits, so stored
     * formats with spaces inside the number are missed and read as "no history", the cautious direction); null when
     * it cannot be read.
     */
    private fun hasSmsHistory(number: String): Boolean? {
        val digits = Addresses.phoneDigits(number) ?: return null
        return guarded {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.ADDRESS} LIKE ?",
                arrayOf("%$digits"),
                null,
            )?.use { it.moveToFirst() }
        }
    }

    private inline fun <T> guarded(block: () -> T?): T? = try {
        block()
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
