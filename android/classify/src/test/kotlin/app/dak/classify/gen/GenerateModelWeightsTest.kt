package app.dak.classify.gen

import app.dak.classify.ClassWeights
import app.dak.classify.NaiveBayesModel
import app.dak.classify.NaiveBayesWeights
import app.dak.classify.Tokenizer
import app.dak.core.model.Category
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Trains the bundled [NaiveBayesModel] weights from [SeedCorpus] and writes them to
 * `src/main/resources/app/dak/classify/model-weights.json`. Running this test regenerates the
 * shipped model whenever the seed corpus changes; it is deterministic given the same corpus.
 */
class GenerateModelWeightsTest {

    @Test
    fun `regenerate bundled model weights from seed corpus`() {
        val byCategory = SeedCorpus.examples.groupBy({ it.first }, { it.second })

        val classes = LinkedHashMap<String, ClassWeights>()
        val vocab = sortedSetOf<String>()
        for (category in Category.entries) {
            val texts = byCategory[category].orEmpty()
            val wordCounts = LinkedHashMap<String, Int>()
            var totalTokens = 0
            for (text in texts) {
                for (token in Tokenizer.tokenize(text)) {
                    wordCounts[token] = (wordCounts[token] ?: 0) + 1
                    totalTokens++
                    vocab += token
                }
            }
            classes[category.name] = ClassWeights(
                docCount = texts.size.coerceAtLeast(1), // never zero: keeps every category reachable
                totalTokens = totalTokens,
                wordCounts = wordCounts,
            )
        }

        val weights = NaiveBayesWeights(vocabSize = vocab.size, classes = classes)
        val json = Json { prettyPrint = true }
        val text = json.encodeToString(NaiveBayesWeights.serializer(), weights)

        val outFile = File("src/main/resources/app/dak/classify/model-weights.json")
        outFile.parentFile.mkdirs()
        outFile.writeText(text)
        assertTrue(outFile.exists() && outFile.length() > 0)

        // Sanity: the freshly trained model should correctly classify a few unseen examples.
        val model = NaiveBayesModel(weights)
        assertPredicts(model, "Your OTP is 555444 for login, do not share with anyone", Category.OTP)
        assertPredicts(model, "Rs 750 debited from your account for a purchase at a store", Category.TRANSACTION)
        assertPredicts(model, "Flat 60% discount sale ends tonight, shop now and save big", Category.PROMOTION)
        assertPredicts(model, "Your account will be blocked, click here to verify your KYC now", Category.SPAM)
        assertPredicts(model, "Kya kar raha hai, chal movie dekhne chalte hai aaj raat", Category.PERSONAL)
    }

    private fun assertPredicts(model: NaiveBayesModel, text: String, expected: Category) {
        val scores = model.predict(text)
        val best = scores.maxByOrNull { it.value }?.key
        assertTrue(best == expected, "expected $expected for \"$text\" but got $best ($scores)")
    }
}
