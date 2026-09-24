package app.dak.automations.rule

/** True for an action that sends the message (or its content) somewhere else. */
public fun ActionSpec.isForwardingOrRelay(): Boolean = when (this) {
    is ActionSpec.ForwardSms -> true
    is ActionSpec.Webhook -> true
    is ActionSpec.RelayToWebClient -> true
    is ActionSpec.RelayRule -> true
    else -> false
}

/** True for an action gated behind a premium [app.dak.premium.Feature]. */
public fun ActionSpec.isPremium(): Boolean = when (this) {
    is ActionSpec.Webhook -> true
    is ActionSpec.RelayToWebClient -> true
    is ActionSpec.RelayRule -> true
    else -> false
}

/**
 * True for an action that can move a message, or anything else, off this phone without the user's hand on it: SMS
 * forwards and auto-replies (both send an SMS), webhooks and relays, and "open" intents (they hand message content to
 * another app). An action this build does not understand ([ActionSpec.Unknown]) counts too, so a rule made by a newer
 * build fails safe. These automations need the app lock (`OutboundAutomationGuard` in :app): someone with a minute on
 * an unlocked phone could otherwise quietly set one up to receive the owner's bank SMS and OTPs.
 */
public fun ActionSpec.sendsOffDevice(): Boolean = when (this) {
    is ActionSpec.ForwardSms,
    is ActionSpec.ScheduleReply,
    is ActionSpec.Webhook,
    is ActionSpec.RelayToWebClient,
    is ActionSpec.RelayRule,
    is ActionSpec.LaunchIntent,
    is ActionSpec.Unknown,
    -> true
    is ActionSpec.Label,
    ActionSpec.Archive,
    is ActionSpec.Notify,
    ActionSpec.Delete,
    -> false
}

/** True when any of the rule's actions [sendsOffDevice]. */
public fun Rule.sendsOffDevice(): Boolean = actions.any { it.sendsOffDevice() }
