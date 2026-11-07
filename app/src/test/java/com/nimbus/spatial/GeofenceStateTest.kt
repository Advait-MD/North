package com.nimbus.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceStateTest {

    @Test
    fun enterThenExit_isOutside() {
        val inside = GeofenceState.onExit(GeofenceState.onEnter(emptySet(), listOf("home")), listOf("home"))
        assertFalse("home" in inside)
    }

    @Test
    fun duplicateEnter_singleExit_isOutside() {
        // Regression: with a List, a second ENTER left a stale copy behind after EXIT,
        // so files stayed unlocked after the user had left the fence.
        var inside = GeofenceState.onEnter(emptySet(), listOf("home"))
        inside = GeofenceState.onEnter(inside, listOf("home"))
        inside = GeofenceState.onExit(inside, listOf("home"))
        assertFalse("home" in inside)
    }

    @Test
    fun overlappingFences_trackedIndependently() {
        var inside = GeofenceState.onEnter(emptySet(), listOf("home", "office"))
        inside = GeofenceState.onExit(inside, listOf("office"))
        assertTrue("home" in inside)
        assertFalse("office" in inside)
    }

    @Test
    fun exitWithoutEnter_isNoOp() {
        assertEquals(setOf("home"), GeofenceState.onExit(setOf("home"), listOf("gym")))
    }
}
