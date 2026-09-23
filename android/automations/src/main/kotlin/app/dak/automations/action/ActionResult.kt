package app.dak.automations.action

import app.dak.premium.Feature

/** Outcome of executing one [app.dak.automations.PlannedAction]. */
public sealed interface ActionResult {
    public data object Success : ActionResult
    public data class Failed(val reason: String) : ActionResult
    /** The action's type needs [feature], which the current [app.dak.premium.Entitlements] does not grant. */
    public data class Locked(val feature: Feature) : ActionResult
}
