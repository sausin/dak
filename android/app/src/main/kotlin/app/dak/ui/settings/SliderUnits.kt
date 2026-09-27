package app.dak.ui.settings

import app.dak.settings.DakSettings
import app.dak.settings.SettingDef

/**
 * What a slider setting's number counts, so [SettingsLabels.sliderValue] can show it with its unit in the app
 * language ("10 minutes", "70 %", "₹5.00") instead of a bare number. Keyed by the registry key: the pure registry
 * keeps plain ints.
 */
internal object SliderUnits {
    enum class Kind { MINUTES, DAYS, DAYS_OR_NEVER, PERCENT, MESSAGES, PAISE, NUMBER }

    fun unitOf(def: SettingDef<*>): Kind = when (def.key) {
        DakSettings.consumedOtpWindowMinutes.key -> Kind.MINUTES
        DakSettings.autoArchivePromosDays.key -> Kind.DAYS_OR_NEVER
        DakSettings.classifierConfidenceThreshold.key -> Kind.PERCENT
        DakSettings.jevMonthlyCap.key -> Kind.MESSAGES
        DakSettings.reconciliationToleranceMinor.key -> Kind.PAISE
        DakSettings.otherBinRetentionDays.key -> Kind.DAYS
        else -> Kind.NUMBER
    }

    /**
     * The 1-based SIM slot whose name setting [def] is, or null. Their default is blank (not "SIM 1"): the name is
     * shown as `sim_n` in the app language until the user types one; names stored by older versions stay as typed.
     */
    fun simSlotOf(def: SettingDef<*>): Int? = when (def.key) {
        DakSettings.sim1Name.key -> 1
        DakSettings.sim2Name.key -> 2
        else -> null
    }
}
