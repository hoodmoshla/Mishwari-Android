package ye.mishwari.geo

/** Pure persisted-state transition rules for ENTER/EXIT geofence events. */
object AlertPolicy {
    const val MIN_COOLDOWN_MILLIS: Long = 30L * 60L * 1000L

    data class State(val inside: Boolean = false, val lastAlertAtMillis: Long = 0L)
    data class Decision(val state: State, val shouldNotify: Boolean, val shouldPersist: Boolean)

    /**
     * EXIT rearms a place. ENTER is ignored when ineligible (notably when alerts
     * are unavailable), suppresses duplicate enters, and enforces a 30-minute
     * minimum interval between successfully displayed alerts for the same place.
     */
    fun onEnter(
        state: State,
        nowMillis: Long,
        hasActiveTasks: Boolean,
        notificationsAllowed: Boolean,
    ): Decision {
        if (!hasActiveTasks || !notificationsAllowed) return Decision(state, false, false)
        if (state.inside) return Decision(state, false, false)
        // Wall-clock adjustments can make a persisted alert timestamp appear to be
        // in the future. Rebase it on this suppressed entry so the place is not
        // blocked until the clock eventually catches up to the old value.
        if (state.lastAlertAtMillis > 0L && nowMillis < state.lastAlertAtMillis) {
            return Decision(state.copy(inside = true, lastAlertAtMillis = nowMillis), false, true)
        }
        val cooldownPassed = state.lastAlertAtMillis <= 0L ||
            nowMillis - state.lastAlertAtMillis >= MIN_COOLDOWN_MILLIS
        return if (cooldownPassed) {
            Decision(State(inside = true, lastAlertAtMillis = nowMillis), true, true)
        } else {
            // Remember the occupied state so duplicate ENTER deliveries do not spam.
            Decision(state.copy(inside = true), false, true)
        }
    }

    fun onExit(state: State): Decision =
        if (state.inside) Decision(state.copy(inside = false), false, true)
        else Decision(state, false, false)
}
