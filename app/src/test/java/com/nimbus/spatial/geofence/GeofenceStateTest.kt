package com.nimbus.spatial.geofence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceStateTest {

    @Test
    fun enterThenExit_isOutside() {
        val inside = GeofenceState.enter(emptySet(), listOf("home")).after
        assertFalse("home" in GeofenceState.exit(inside, listOf("home")).after)
    }

    @Test
    fun duplicateEnter_thenSingleExit_isOutside() {
        // Regression: with a List, a second ENTER left a stale copy behind after EXIT,
        // so files stayed unlocked after the user had left.
        var inside = GeofenceState.enter(emptySet(), listOf("home")).after
        inside = GeofenceState.enter(inside, listOf("home")).after
        assertFalse("home" in GeofenceState.exit(inside, listOf("home")).after)
    }

    @Test
    fun duplicateEnter_changesNothing() {
        // Re-registering fences repeats ENTER; that must not trigger a notification or wallpaper change.
        val change = GeofenceState.enter(setOf("home"), listOf("home"))
        assertTrue(change.changedIds.isEmpty())
        assertFalse(change.insideAnyChanged)
    }

    @Test
    fun firstEnter_and_lastExit_changeInsideAny() {
        assertTrue(GeofenceState.enter(emptySet(), listOf("home")).insideAnyChanged)
        assertTrue(GeofenceState.exit(setOf("home"), listOf("home")).insideAnyChanged)
    }

    @Test
    fun overlappingFences_trackedSeparately() {
        val change = GeofenceState.exit(setOf("home", "office"), listOf("office"))
        assertEquals(setOf("home"), change.after)
        assertEquals(setOf("office"), change.changedIds)
        assertFalse("still inside home, so the wallpaper stays", change.insideAnyChanged)
    }

    @Test
    fun exitFromFenceNotInside_changesNothing() {
        val change = GeofenceState.exit(setOf("home"), listOf("gym"))
        assertEquals(setOf("home"), change.after)
        assertTrue(change.changedIds.isEmpty())
    }
}
