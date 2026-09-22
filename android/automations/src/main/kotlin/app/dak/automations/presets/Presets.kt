package app.dak.automations.presets

import app.dak.automations.rule.ActionSpec
import app.dak.automations.rule.Condition
import app.dak.automations.rule.Recurrence
import app.dak.automations.rule.Rule
import app.dak.automations.rule.ScheduleSpec
import app.dak.automations.rule.Trigger
import app.dak.core.model.Category

/**
 * A handful of built-in example rules, exportable/importable via `app.dak.automations.rule.RuleCodec`
 * (they are ordinary [Rule]s; nothing about them is special once created). `:app` offers them as
 * one-tap starting points in the automations UI.
 */
public object Presets {

    /**
     * Archives promotional messages on arrival. "Older than N days" (as named in the build plan) is a
     * sweep over already-indexed messages, not a per-arrival condition the current AST expresses; the
     * daily [ScheduleSpec] trigger documents the intended cadence for that sweep, run by whatever
     * component walks the index (outside this module's pure-evaluation scope).
     */
    public fun archiveOldPromotions(now: Long): Rule = Rule(
        id = "preset-archive-old-promotions",
        name = "Archive promotions older than 14 days",
        trigger = Trigger.Schedule(
            ScheduleSpec.Recurring(Recurrence.Daily(hour = 3, minute = 0, zoneId = "Asia/Kolkata")),
        ),
        conditions = Condition.CategoryIs(Category.PROMOTION),
        actions = listOf(ActionSpec.Archive),
        createdAt = now,
        updatedAt = now,
    )

    /** Labels messages that look like an Amazon delivery/order update. */
    public fun labelAmazonDeliveries(now: Long): Rule = Rule(
        id = "preset-label-amazon-deliveries",
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
        id = "preset-otp-big-notification",
        name = "Loud, big OTP notification",
        trigger = Trigger.MessageReceived(),
        conditions = Condition.HasOtp,
        actions = listOf(ActionSpec.Notify(title = "OTP", text = "{otp}")),
        createdAt = now,
        updatedAt = now,
    )

    public fun all(now: Long): List<Rule> = listOf(
        archiveOldPromotions(now),
        labelAmazonDeliveries(now),
        otpBigNotification(now),
    )
}
