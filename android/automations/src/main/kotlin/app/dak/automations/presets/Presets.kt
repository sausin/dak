package app.dak.automations.presets

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Condition
import app.dak.automations.rule.Recurrence
import app.dak.automations.rule.Rule
import app.dak.automations.rule.ScheduleSpec
import app.dak.automations.rule.Trigger
import app.dak.core.model.Category
import java.time.ZoneId

/**
 * A handful of built-in example rules, exportable/importable via `app.dak.automations.rule.RuleCodec`
 * (they are ordinary [Rule]s; nothing about them is special once created). `:app` offers them as
 * one-tap starting points in the automations UI. The names here are English; `:app` passes the name in the app
 * language for each preset id ([all]'s `localizedName`), and a saved preset keeps the name it was saved with.
 */
public object Presets {

    public const val ID_ARCHIVE_OLD_PROMOTIONS: String = "preset-archive-old-promotions"
    public const val ID_LABEL_AMAZON_DELIVERIES: String = "preset-label-amazon-deliveries"
    public const val ID_OTP_BIG_NOTIFICATION: String = "preset-otp-big-notification"

    /**
     * Archives promotional messages on arrival. "Older than N days" (as named in the build plan) is a
     * sweep over already-indexed messages, not a per-arrival condition the current AST expresses; the
     * daily [ScheduleSpec] trigger documents the intended cadence for that sweep, run by whatever
     * component walks the index (outside this module's pure-evaluation scope). [zoneId] is the time zone the 03:00
     * runs in (the device's by default).
     */
    public fun archiveOldPromotions(now: Long, zoneId: String = ZoneId.systemDefault().id): Rule = Rule(
        id = ID_ARCHIVE_OLD_PROMOTIONS,
        name = "Archive promotions older than 14 days",
        trigger = Trigger.Schedule(
            ScheduleSpec.Recurring(Recurrence.Daily(hour = 3, minute = 0, zoneId = zoneId)),
        ),
        conditions = Condition.CategoryIs(Category.PROMOTION),
        actions = listOf(ActionSpec.Archive),
        createdAt = now,
        updatedAt = now,
    )

    /** Labels messages that look like an Amazon delivery/order update. */
    public fun labelAmazonDeliveries(now: Long): Rule = Rule(
        id = ID_LABEL_AMAZON_DELIVERIES,
        name = "Label Amazon deliveries",
        trigger = Trigger.MessageReceived(),
        conditions = Condition.Any(
            listOf(
                Condition.SenderMatches("(?i)amazon"),
                Condition.BodyMatches("(?i)\\bamazon\\b.*(order|shipped|out for delivery|delivered)"),
            ),
        ),
        actions = listOf(ActionSpec.Label("Amazon")),
        createdAt = now,
        updatedAt = now,
    )

    /** Sends a large, tap-to-copy-friendly notification for every OTP (mirrors the most-requested SMS Organizer feature). */
    public fun otpBigNotification(now: Long): Rule = Rule(
        id = ID_OTP_BIG_NOTIFICATION,
        name = "Loud, big OTP notification",
        trigger = Trigger.MessageReceived(),
        conditions = Condition.HasOtp,
        actions = listOf(ActionSpec.Notify(title = "OTP", text = "{otp}")),
        createdAt = now,
        updatedAt = now,
    )

    /**
     * Every preset. [localizedName] gives the display name for a preset id (`:app` reads it from resources); null
     * keeps the English name.
     */
    public fun all(now: Long, localizedName: (id: String) -> String? = { null }): List<Rule> = listOf(
        archiveOldPromotions(now),
        labelAmazonDeliveries(now),
        otpBigNotification(now),
    ).map { rule -> localizedName(rule.id)?.takeIf { it.isNotBlank() }?.let { rule.copy(name = it) } ?: rule }
}
