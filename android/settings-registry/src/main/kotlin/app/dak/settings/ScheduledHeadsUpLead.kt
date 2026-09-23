package app.dak.settings

/**
 * Values of [DakSettings.scheduledHeadsUp]: how long before a scheduled message goes out Dak posts its heads-up
 * notification (Send now / Delay / Cancel). [OFF] turns heads-ups off for every scheduled message, birthday wishes
 * included.
 */
object ScheduledHeadsUpLead {
    const val OFF = "off"
    const val DEFAULT = "15"

    /** Choice options in the order the settings dialog lists them. */
    val options: List<ChoiceOption> = listOf(
        ChoiceOption(OFF, "Off"),
        ChoiceOption("5", "5 minutes before"),
        ChoiceOption("15", "15 minutes before"),
        ChoiceOption("60", "1 hour before"),
    )

    /** Lead time in minutes for a stored [value], or null when heads-ups are off. Unknown values use [DEFAULT]. */
    fun minutesOf(value: String?): Int? {
        if (value == OFF) return null
        val known = options.firstOrNull { it.value == value && it.value != OFF }?.value ?: DEFAULT
        return known.toInt()
    }
}
