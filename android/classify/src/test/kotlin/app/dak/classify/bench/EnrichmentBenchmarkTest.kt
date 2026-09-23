package app.dak.classify.bench

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Indexing throughput benchmark (msgs/sec) over [SyntheticCorpus]. Opt-in: excluded from `test` unless Gradle gets
 * `-Pdak.bench=true` (see `classify/build.gradle.kts` and docs/performance.md):
 *
 * ```
 * DAK_JVM_HARNESS_DIR=<dir> GRADLE=... scripts/jvm-test.sh :classify:test -Pdak.bench=true --tests '*EnrichmentBenchmarkTest*'
 * ```
 *
 * Results are printed and appended to `classify/build/reports/dak-bench.txt`.
 */
class EnrichmentBenchmarkTest {

    private val corpus = SyntheticCorpus.generate(CORPUS_SIZE)

    @Test
    fun benchmark() {
        assumeTrue("set -Pdak.bench=true", System.getProperty("dak.bench") == "true")
        val lines = ArrayList<String>()
        lines += "corpus=${corpus.size} msgs, distinct bodies=${corpus.map { it.body }.toSet().size}, " +
            "cores=${Runtime.getRuntime().availableProcessors()}, jvm=${System.getProperty("java.version")}"
        for ((name, factory) in variants()) {
            lines += measure("$name classify-only 1 thread") { path -> runBlocking { corpus.forEach { path.pipeline.classify(it.address, it.body, it.subId) } } }
                .let { it(factory) }
            lines += measure("$name full-path 1 thread") { path -> runBlocking { corpus.forEach { path.enrich(it) } } }
                .let { it(factory) }
            lines += measure("$name full-path $THREADS threads") { path -> parallel(path) }
                .let { it(factory) }
        }
        val report = lines.joinToString("\n")
        println(report)
        File("build/reports").mkdirs()
        File("build/reports/dak-bench.txt").appendText("---\n$report\n")
    }

    private fun parallel(path: EnrichmentPath) = runBlocking(Dispatchers.Default) {
        corpus.chunked(BATCH).forEach { batch ->
            batch.chunked((batch.size + THREADS - 1) / THREADS).map { slice ->
                async { slice.forEach { path.enrich(it) } }
            }.awaitAll()
        }
    }

    /** Warms up, then times [RUNS] passes over the corpus with a fresh path per pass; reports the median. */
    private fun measure(label: String, pass: (EnrichmentPath) -> Unit): (() -> EnrichmentPath) -> String = { factory ->
        repeat(WARMUP) { pass(factory()) }
        val rates = List(RUNS) {
            val path = factory()
            val start = System.nanoTime()
            pass(path)
            corpus.size / ((System.nanoTime() - start) / 1e9)
        }.sorted()
        "%-46s %,10.0f msgs/s (min %,.0f, max %,.0f)".format(label, rates[rates.size / 2], rates.first(), rates.last())
    }

    /** The configurations compared; each factory builds a fresh (cold-cache) path. */
    private fun variants(): List<Pair<String, () -> EnrichmentPath>> = listOf(
        "current" to { EnrichmentPath.create() },
    )

    private companion object {
        const val CORPUS_SIZE = 50_000
        const val WARMUP = 2
        const val RUNS = 5
        const val THREADS = 3
        const val BATCH = 500
    }
}
