package app.dak.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.BiometricManager
import app.dak.telephony.SimRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies the registry's [DeviceContext] (hides rows that cannot apply, e.g. SIM 2 rows on a single-SIM phone). */
@Singleton
class DeviceContextProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sims: SimRepository,
) {
    private val hasBiometric: Boolean by lazy {
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun build(simCount: Int) = DeviceContext(
        simCount = simCount,
        hasMmsData = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
        apiLevel = Build.VERSION.SDK_INT,
        hasBiometric = hasBiometric,
    )

    fun current(): DeviceContext = build(sims.sims.value.size)

    val updates: Flow<DeviceContext> = sims.sims.map { build(it.size) }
}
