package com.nimbus.spatial.geofence

/** Which fences the user was inside before and after an ENTER/EXIT event. */
data class InsideStateChange(val before: Set<String>, val after: Set<String>) {

    /** Fences that actually flipped. Empty for a repeated event, e.g. ENTER for a fence we're already in. */
    val changedIds: Set<String> get() = (after - before) + (before - after)

    /** True when the user went from "inside no fence" to "inside some fence", or the reverse. */
    val insideAnyChanged: Boolean get() = before.isEmpty() != after.isEmpty()
}

/**
 * Pure logic with no Android dependencies, unit-tested in GeofenceStateTest.
 * Using sets means duplicate events can't leave a stale entry behind.
 */
object GeofenceState {
    fun enter(inside: Set<String>, ids: Collection<String>) = InsideStateChange(inside, inside + ids)

    fun exit(inside: Set<String>, ids: Collection<String>) = InsideStateChange(inside, inside - ids.toSet())
}
