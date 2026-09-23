package app.dak.index.enrich

import app.dak.classify.NaiveBayesModel
import app.dak.classify.TemplateBundle

/**
 * The bundled classifier resources, parsed at most once per process (lazily, on the first thread that needs them
 * - always a background thread on the message path). Both objects are immutable and safe to share, so the index
 * enricher and the notification classifier use the same instances instead of each parsing the JSON again.
 */
object ClassifierAssets {
    /** The bundled default templates (`TemplateBundle.loadDefault()`), parsed once. */
    val defaultTemplates: TemplateBundle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { TemplateBundle.loadDefault() }

    /** The bundled Naive Bayes model (`NaiveBayesModel.loadDefault()`), parsed once. */
    val model: NaiveBayesModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { NaiveBayesModel.loadDefault() }
}
