package com.nimbus.spatial

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure geometry helpers, kept free of Android types so they can be unit-tested on the JVM. */
object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Android allows at most 100 registered geofences per app. */
    const val MAX_GEOFENCES = 100

    /** Slack given for GPS error, capped so a poor fix can't unlock places far away. */
    const val MAX_ACCURACY_SLACK_M = 50.0

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun isInside(
        lat: Double, lng: Double, accuracyM: Double,
        centerLat: Double, centerLng: Double, radiusM: Double
    ): Boolean {
        val slack = min(accuracyM.coerceAtLeast(0.0), MAX_ACCURACY_SLACK_M)
        return distanceMeters(lat, lng, centerLat, centerLng) <= radiusM + slack
    }

    /** The [limit] items closest to (lat, lng), nearest first. */
    fun <T> nearest(
        items: Collection<T>, lat: Double, lng: Double,
        limit: Int = MAX_GEOFENCES, position: (T) -> Pair<Double, Double>
    ): List<T> = items
        .map { it to position(it).let { (pLat, pLng) -> distanceMeters(lat, lng, pLat, pLng) } }
        .sortedBy { it.second }
        .take(limit)
        .map { it.first }
}
