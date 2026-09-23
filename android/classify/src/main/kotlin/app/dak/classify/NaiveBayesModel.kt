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

    // Precomputed log-probabilities (same expressions, same summation order as the naive loop, so predictions are
    // bit-identical): one map lookup per token instead of one per token and class, and no `ln` on the hot path.
    private val categories: List<Category>
    private val priorLog: DoubleArray
    private val unknownLog: DoubleArray
    private val tokenLog: HashMap<String, DoubleArray>

    init {
        val used = weights.classes.entries.mapNotNull { (name, cw) ->
            runCatching { Category.valueOf(name) }.getOrNull()?.let { it to cw }
        }
        categories = used.map { it.first }
        priorLog = DoubleArray(used.size) { i ->
            val prior = used[i].second.docCount.toDouble() / totalDocs
            ln(prior.coerceAtLeast(1e-9))
        }
        val denoms = DoubleArray(used.size) { i -> (used[i].second.totalTokens + weights.vocabSize).toDouble() }
        unknownLog = DoubleArray(used.size) { i -> ln((0 + 1).toDouble() / denoms[i]) }
        tokenLog = HashMap()
        for ((_, cw) in used) {
            for (token in cw.wordCounts.keys) {
                tokenLog.getOrPut(token) {
                    DoubleArray(used.size) { i -> ln(((used[i].second.wordCounts[token] ?: 0) + 1).toDouble() / denoms[i]) }
                }
            }
        }
    }

    /** True when [token] (a [Tokenizer] token) has a weight in some class; unknown tokens all score alike. */
    public fun knows(token: String): Boolean = tokenLog.containsKey(token)

    override fun predict(text: String): Map<Category, Float> {
        val tokens = Tokenizer.tokenize(text)
        val scores = priorLog.copyOf()
        for (token in tokens) {
            val logs = tokenLog[token] ?: unknownLog
            for (i in scores.indices) scores[i] += logs[i]
        }
        val logScores = LinkedHashMap<Category, Double>(categories.size * 2)
        for (i in categories.indices) logScores[categories[i]] = scores[i]
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
