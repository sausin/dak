package app.dak.telephony.provider

/**
 * Minimal column lists for the provider reads that page through whole tables (backfill, reconcile), instead of
 * `null` projections that ship every column of every row (and make the provider's sorter carry them all).
 *
 * OEM tolerance is kept: the reader first asks the provider which columns a table really has (an empty `_id < 0`
 * query, once per process) and requests only the [wanted] columns that exist, so a schema without `sub_id` but with
 * `sim_id` / `sim_slot` still resolves the subscription exactly as before. If the probe fails, or a column the
 * mapping cannot do without is missing, the read falls back to the `null` projection. Pure; no Android types.
 */
internal object ProviderProjections {

    private val subscriptionColumns = SmsColumns.ALT_SUBSCRIPTION_COLUMNS + SmsColumns.SLOT_COLUMNS

    // Keep these lists in sync with the Cursor mapping functions in TelephonyProviderReader: a column read there but
    // missing here silently reads as its default. ProviderProjectionsTest checks the mapping source against them.

    /** Columns `toSms()` reads. */
    val SMS: List<String> = listOf(
        SmsColumns.ID, SmsColumns.THREAD_ID, SmsColumns.ADDRESS, SmsColumns.BODY, SmsColumns.DATE, SmsColumns.DATE_SENT,
        SmsColumns.TYPE, SmsColumns.STATUS, SmsColumns.READ, SmsColumns.SEEN, SmsColumns.SUBSCRIPTION_ID,
    ) + subscriptionColumns

    /** Columns `toMmsRow()` reads. */
    val MMS: List<String> = listOf(
        MmsColumns.ID, MmsColumns.THREAD_ID, MmsColumns.DATE, MmsColumns.MESSAGE_BOX, MmsColumns.READ, MmsColumns.SEEN,
        MmsColumns.SUBJECT, MmsColumns.STATUS, MmsColumns.DELIVERY_REPORT, MmsColumns.SUBSCRIPTION_ID,
    ) + subscriptionColumns

    /** Columns `loadParts()` reads (never `_data`, the part file path). */
    val MMS_PART: List<String> = listOf(
        MmsPartColumns.ID, MmsPartColumns.MSG_ID, MmsPartColumns.SEQ, MmsPartColumns.CONTENT_TYPE, MmsPartColumns.TEXT,
        MmsPartColumns.CHARSET, MmsPartColumns.NAME, MmsPartColumns.FILENAME, MmsPartColumns.CONTENT_LOCATION,
        MmsPartColumns.CONTENT_ID,
    )

    /** Columns `mmsAddress()` reads. */
    val MMS_ADDR: List<String> = listOf(MmsAddrColumns.ADDRESS, MmsAddrColumns.TYPE)

    /** Selection for the column probe: a valid query that matches no row. */
    const val PROBE_SELECTION: String = "_id < 0"

    /**
     * The [wanted] columns present in [available] (in [wanted] order), or null (= all columns) when [available] is
     * unknown or lacks one of [required].
     */
    fun select(wanted: List<String>, available: Array<String>?, required: List<String> = listOf(SmsColumns.ID)): Array<String>? {
        if (available == null) return null
        val present = available.toHashSet()
        if (required.any { it !in present }) return null
        return wanted.filter { it in present }.toTypedArray()
    }
}
