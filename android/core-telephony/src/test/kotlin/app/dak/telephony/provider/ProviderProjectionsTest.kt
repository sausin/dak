package app.dak.telephony.provider

import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderProjectionsTest {

    @Test
    fun selectsOnlyColumnsTheProviderHas() {
        val oem = arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "seen", "sim_id", "locked", "extra")
        val selected = ProviderProjections.select(ProviderProjections.SMS, oem)!!
        assertContentEquals(arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "seen", "sim_id"), selected)
    }

    @Test
    fun fallsBackToAllColumnsWhenUnknownOrIncomplete() {
        assertNull(ProviderProjections.select(ProviderProjections.SMS, null))
        assertNull(ProviderProjections.select(ProviderProjections.SMS, arrayOf("thread_id", "body")))
    }

    /**
     * Guard against drift: every column constant the Cursor mapping code reads must be in the matching projection,
     * or it would silently read as its default once the projection is applied.
     */
    @Test
    fun projectionsCoverEveryColumnTheMappingReads() {
        val source = File("src/main/kotlin/app/dak/telephony/provider/TelephonyProviderReader.kt").readText()
        val sms = section(source, "// --- SMS", "// --- MMS")
        val mms = section(source, "// --- MMS", "// --- Threads")
        check(sms, "SmsColumns", stringConstants(SmsColumns::class.java), ProviderProjections.SMS)
        check(fn(mms, "fun Cursor.toMmsRow"), "MmsColumns", stringConstants(MmsColumns::class.java), ProviderProjections.MMS)
        check(fn(mms, "fun loadParts"), "MmsPartColumns", stringConstants(MmsPartColumns::class.java), ProviderProjections.MMS_PART)
        check(fn(mms, "fun mmsAddress"), "MmsAddrColumns", stringConstants(MmsAddrColumns::class.java) - "INSERT_ADDRESS_TOKEN", ProviderProjections.MMS_ADDR)
    }

    private fun section(source: String, from: String, to: String): String =
        source.substring(source.indexOf(from).also { assertTrue(it >= 0, from) }, source.indexOf(to).also { assertTrue(it >= 0, to) })

    private fun fn(section: String, signature: String): String {
        val start = section.indexOf(signature).also { assertTrue(it >= 0, signature) }
        val end = section.indexOf("\n    private ", start + 1).let { if (it < 0) section.length else it }
        return section.substring(start, end)
    }

    private fun stringConstants(type: Class<*>): Set<String> =
        type.declaredFields.filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()

    private fun check(code: String, owner: String, columnConstants: Set<String>, projection: List<String>) {
        val values = Class.forName("app.dak.telephony.provider.$owner").let { cls ->
            columnConstants.associateWith { name -> cls.getDeclaredField(name).apply { isAccessible = true }.get(null) as String }
        }
        val referenced = Regex("""$owner\.([A-Z_]+)""").findAll(code).map { it.groupValues[1] }.filter { it in values }.toSet()
        assertTrue(referenced.isNotEmpty(), "no $owner columns found")
        for (name in referenced) assertTrue(values.getValue(name) in projection, "$owner.$name is read but missing from the projection")
    }
}
