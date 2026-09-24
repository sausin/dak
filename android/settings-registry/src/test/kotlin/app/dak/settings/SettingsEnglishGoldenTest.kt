package app.dak.settings

import app.dak.premium.FreeEntitlements
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the English text every Settings row renders (titles, summaries, choice labels, group names) and the order
 * search returns rows in for a fixed set of queries. Written before the labels moved to string resources
 * (docs/i18n.md) so that refactor provably changed neither what English users read nor how search ranks.
 *
 * Deliberate wording or ranking changes: rerun with `DAK_UPDATE_GOLDEN=1` in the environment and review the diff of
 * `src/test/resources/settings-*.golden.txt`.
 */
class SettingsEnglishGoldenTest {

    @Test
    fun `english labels match the golden`() = checkGolden("settings-english.golden.txt", englishLines())

    @Test
    fun `search ranking matches the golden`() = checkGolden("settings-search.golden.txt", searchLines())

    private fun englishLines(): List<String> = buildList {
        SettingsGroup.entries.forEach { add("group|${it.name}|${it.displayName}") }
        DakSettings.all.forEach { def ->
            add("title|${def.key}|${def.title}")
            add("summary|${def.key}|${def.summary}")
            (def.control as? ControlType.SingleChoice)?.options?.forEach { add("option|${def.key}|${it.value}|${it.label}") }
        }
    }

    private fun searchLines(): List<String> {
        val device = DeviceContext(simCount = 2, hasMmsData = true, apiLevel = 34)
        return QUERIES.map { q ->
            val keys = SettingsSearch.search(q, device, FreeEntitlements).map { it.key }
            "$q => ${keys.joinToString(",")}"
        }
    }

    private fun checkGolden(name: String, actual: List<String>) {
        val file = File("src/test/resources/$name")
        if (System.getenv("DAK_UPDATE_GOLDEN") == "1") {
            file.parentFile.mkdirs()
            file.writeText(actual.joinToString("\n", postfix = "\n"))
        }
        val golden = javaClass.classLoader.getResource(name)?.readText()?.lines()?.filter { it.isNotEmpty() }
            ?: file.takeIf { it.exists() }?.readLines()?.filter { it.isNotEmpty() }
            ?: error("missing golden $name; run with DAK_UPDATE_GOLDEN=1")
        assertEquals(golden.joinToString("\n"), actual.joinToString("\n"), "$name drifted")
    }

    private companion object {
        val QUERIES = listOf(
            "otp", "otp auto", "one time password", "balanc", "sim", "sim 2", "translate", "privacy", "privacy policy",
            "delete my data", "consent", "gdpr", "swipe", "backup", "lock", "app lock", "notification", "roaming",
            "forward", "birthday", "currency", "mms", "bin", "code", "scam", "enter", "heads up", "webhook",
        )
    }
}
