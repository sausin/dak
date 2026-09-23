package app.dak.classify

import app.dak.core.model.Category

/**
 * A pluggable text classifier over [Category]. The default implementation ([NaiveBayesModel]) is a
 * small pure-Kotlin multinomial Naive Bayes model; this seam lets a TFLite/ONNX model swap in later
 * without touching [ClassifierPipeline].
 */
public fun interface MessageModel {
    /** Returns a probability-like score (summing to ~1.0) per category for [text]. */
    public fun predict(text: String): Map<Category, Float>
}
