package app.dak.core.model

import kotlinx.serialization.Serializable

/** An active (or remembered) SIM subscription. Multi-SIM is a first-class dimension everywhere. */
@Serializable
data class SimInfo(
    val subId: Int,
    /** 0-based physical/logical slot, -1 if unknown (e.g. removed SIM). */
    val slotIndex: Int,
    val displayName: String,
    val carrierName: String? = null,
    /** ISO 3166-1 alpha-2, lower or upper case, as reported by the SIM (home country, not network). */
    val countryIso: String? = null,
    val colorArgb: Int = 0,
    val number: String? = null,
    val isEmbedded: Boolean = false,
    /** False when the SIM has been removed; its chip greys out. */
    val isActive: Boolean = true,
)
