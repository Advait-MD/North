package com.nimbus.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    private val lat = 28.6129
    private val lng = 77.2295
    private fun north(m: Double) = lat + m / 111_195.0

    @Test
    fun distance_isAbout111kmPerDegreeOfLatitude() {
        assertEquals(111_195.0, GeoMath.distanceMeters(0.0, 0.0, 1.0, 0.0), 10.0)
        assertEquals(0.0, GeoMath.distanceMeters(lat, lng, lat, lng), 0.0)
    }

    @Test
    fun isInside_respectsRadiusAndCappedAccuracy() {
        assertTrue(GeoMath.isInside(north(30.0), lng, 5.0, lat, lng, 50.0))
        assertFalse(GeoMath.isInside(north(80.0), lng, 5.0, lat, lng, 50.0))
        assertTrue(GeoMath.isInside(north(80.0), lng, 40.0, lat, lng, 50.0))
        // A 1 km accuracy claim only buys MAX_ACCURACY_SLACK_M of slack.
        assertFalse(GeoMath.isInside(north(150.0), lng, 1000.0, lat, lng, 50.0))
    }

    @Test
    fun nearest_keepsTheClosestUpToTheLimit() {
        val points = (1..150).map { it to north(it * 100.0) }.shuffled()
        val picked = GeoMath.nearest(points, lat, lng) { it.second to lng }
        assertEquals(GeoMath.MAX_GEOFENCES, picked.size)
        assertEquals((1..100).toList(), picked.map { it.first })
    }
}
