package app.dak.index.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Room type converters. Enums need none (Room stores them by name). */
class IndexConverters {
    @TypeConverter
    fun labelsToJson(labels: Set<String>): String = IndexJson.json.encodeToString(STRING_LIST, labels.sorted())

    @TypeConverter
    fun labelsFromJson(json: String): Set<String> =
        if (json.isEmpty()) emptySet() else runCatching { IndexJson.json.decodeFromString(STRING_LIST, json).toSet() }.getOrDefault(emptySet())

    private companion object {
        val STRING_LIST = ListSerializer(String.serializer())
    }
}

/** The JSON configuration used for every JSON column of the index. */
internal object IndexJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
