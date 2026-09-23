package app.dak.finance.rates

import kotlinx.serialization.json.Json

/** Loads the [RatesTable] bundled as a resource with the app (refreshed by the OTA template job elsewhere). */
object RatesLoader {

    private val json = Json { ignoreUnknownKeys = true }

    /** The sample table bundled inside this module's resources, used as the seed/fallback rates. */
    fun loadBundled(): RatesTable {
        val stream = RatesLoader::class.java.getResourceAsStream("/app/dak/finance/sample-rates.json")
            ?: return RatesTable.fallback()
        return stream.use { json.decodeFromString(RatesTable.serializer(), it.readBytes().decodeToString()) }
    }

    /** Parses a [RatesTable] from a freshly fetched JSON payload (same shape as the bundled sample). */
    fun parse(jsonText: String): RatesTable = json.decodeFromString(RatesTable.serializer(), jsonText)
}
