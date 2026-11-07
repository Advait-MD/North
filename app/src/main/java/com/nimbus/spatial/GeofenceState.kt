package com.nimbus.spatial

/**
 * Pure logic for tracking which geofences the user is currently inside.
 *
 * Uses set semantics so repeated ENTER events (e.g. INITIAL_TRIGGER_ENTER firing again
 * when a fence is re-registered) can't leave a stale entry behind after a single EXIT.
 */
object GeofenceState {
    fun onEnter(inside: Set<String>, ids: Collection<String>): Set<String> = inside + ids

    fun onExit(inside: Set<String>, ids: Collection<String>): Set<String> = inside - ids.toSet()
}
