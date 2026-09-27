package app.dak.ui.common

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.dak.R
import app.dak.core.model.SimInfo

/**
 * The name shown for a SIM: the name the phone gave it, else "SIM 1" (`sim_n`) in the app language. The telephony
 * layer leaves [SimInfo.displayName] blank for an unnamed SIM (it cannot see app resources), so every label goes
 * through here rather than reading [SimInfo.displayName] directly.
 */
object SimNames {
    fun label(resources: Resources, sim: SimInfo): String = when {
        sim.displayName.isNotBlank() -> sim.displayName
        sim.slotIndex >= 0 -> resources.getString(R.string.sim_n, sim.slotIndex + 1)
        !sim.carrierName.isNullOrBlank() -> sim.carrierName.orEmpty()
        else -> resources.getString(R.string.sim_no_slot)
    }
}

/** [SimNames.label] in the composition's language. */
@Composable
@ReadOnlyComposable
fun simName(sim: SimInfo): String {
    LocalConfiguration.current // recompose when the configuration (language) changes, like stringResource
    return SimNames.label(LocalContext.current.resources, sim)
}
