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
