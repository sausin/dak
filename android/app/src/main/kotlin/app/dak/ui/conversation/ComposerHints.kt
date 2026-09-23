package app.dak.ui.conversation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Counts how often the "sent as +91…" normalisation hint was shown, so it appears only the first few times. */
@Singleton
class ComposerHints @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("dak_composer_hints", Context.MODE_PRIVATE)

    fun shouldShowNormalizationHint(): Boolean = prefs.getInt(KEY_NORMALIZED, 0) < MAX_TIMES

    fun normalizationHintShown() {
        prefs.edit().putInt(KEY_NORMALIZED, prefs.getInt(KEY_NORMALIZED, 0) + 1).apply()
    }

    private companion object {
        const val KEY_NORMALIZED = "normalized_hint_count"
        const val MAX_TIMES = 3
    }
}
