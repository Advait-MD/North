package com.nimbus.spatial.security

/**
 * Pure decision logic for the live-location gate (no Android dependencies, unit-tested in
 * FenceCheckTest). LiveLocationGate feeds it a fresh location fix.
 */
object FenceCheck {

    /** A fix less precise than this can't tell inside from outside, so it's rejected. */
    const val MAX_ACCURACY_METERS = 100f

    enum class Result { INSIDE, OUTSIDE, MOCK_LOCATION, INACCURATE, NO_FIX }

    /**
     * @param distanceMeters distance from the fix to the fence's center
     * @param accuracyMeters the fix's accuracy radius; the fix counts as inside if that circle
     *        overlaps the fence, so small fences still work with ordinary GPS accuracy
     */
    fun classify(isMock: Boolean, distanceMeters: Float, accuracyMeters: Float, radiusMeters: Float): Result =
        when {
            isMock -> Result.MOCK_LOCATION
            accuracyMeters > MAX_ACCURACY_METERS -> Result.INACCURATE
            distanceMeters <= radiusMeters + accuracyMeters -> Result.INSIDE
            else -> Result.OUTSIDE
        }
}
