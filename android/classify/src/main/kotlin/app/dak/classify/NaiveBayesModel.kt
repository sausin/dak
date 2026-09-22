package app.dak.classify

import app.dak.core.model.Category
import kotlin.math.exp
import kotlin.math.ln

/** Serializable weights for [NaiveBayesModel], trained offline by the generator in the test sources. */
@kotlinx.serialization.Serializable
public data class ClassWeights(
    val docCount: Int,
    val totalTokens: Int,
    val wordCounts: Map<String, Int>,
)

@kotlinx.serialization.Serializable
public data class NaiveBayesWeights(
    val vocabSize: Int,
    /** Category name ([Category.name]) -> weights. */
    val classes: Map<String, ClassWeights>,
)

/**
 * A small multinomial Naive Bayes text classifier with Laplace (add-one) smoothing, trained on a
 * hand-written seed corpus (see `src/test/kotlin` generator) and shipped as a JSON resource.
 */
public class NaiveBayesModel(private val weights: NaiveBayesWeights) : MessageModel {

    private val totalDocs = weights.classes.values.sumOf { it.docCount }.coerceAtLeast(1)

    override fun predict(text: String): Map<Category, Float> {
        val tokens = Tokenizer.tokenize(text)
        val logScores = LinkedHashMap<Category, Double>()
        for (categoryName in weights.classes.keys) {
            val category = runCatching { Category.valueOf(categoryName) }.getOrNull() ?: continue
            val cw = weights.classes.getValue(categoryName)
            val prior = cw.docCount.toDouble() / totalDocs
            var score = ln(prior.coerceAtLeast(1e-9))
            val denom = (cw.totalTokens + weights.vocabSize).toDouble()
            for (token in tokens) {
                val count = cw.wordCounts[token] ?: 0
                score += ln((count + 1).toDouble() / denom)
            }
            logScores[category] = score
        }
        return softmax(logScores)
    }

    private fun softmax(logScores: Map<Category, Double>): Map<Category, Float> {
        if (logScores.isEmpty()) return emptyMap()
        val max = logScores.values.max()
        val exps = logScores.mapValues { exp(it.value - max) }
        val sum = exps.values.sum().coerceAtLeast(1e-12)
        return exps.mapValues { (it.value / sum).toFloat() }
    }

    public companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /** Loads the bundled pre-trained weights resource. */
        public fun loadDefault(): NaiveBayesModel {
            val stream = requireNotNull(
                NaiveBayesModel::class.java.getResourceAsStream("/app/dak/classify/model-weights.json"),
            ) { "model-weights.json resource missing" }
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return NaiveBayesModel(json.decodeFromString(NaiveBayesWeights.serializer(), text))
        }

        public fun parse(jsonText: String): NaiveBayesModel =
            NaiveBayesModel(json.decodeFromString(NaiveBayesWeights.serializer(), jsonText))
    }
}
