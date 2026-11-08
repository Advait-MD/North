package com.nimbus.spatial.security

import com.nimbus.spatial.security.FenceCheck.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FenceCheckTest {

    @Test
    fun accurateFixInsideFence_isInside() {
        assertEquals(Result.INSIDE, FenceCheck.classify(isMock = false, distanceMeters = 20f, accuracyMeters = 5f, radiusMeters = 50f))
    }

    @Test
    fun fixClearlyOutside_isOutside() {
        assertEquals(Result.OUTSIDE, FenceCheck.classify(isMock = false, distanceMeters = 500f, accuracyMeters = 10f, radiusMeters = 50f))
    }

    @Test
    fun accuracyCircleOverlappingSmallFence_isInside() {
        // 10 m fence, typical 15 m GPS accuracy, standing 20 m from the center.
        assertEquals(Result.INSIDE, FenceCheck.classify(isMock = false, distanceMeters = 20f, accuracyMeters = 15f, radiusMeters = 10f))
    }

    @Test
    fun mockLocation_isRejectedEvenIfInside() {
        assertEquals(Result.MOCK_LOCATION, FenceCheck.classify(isMock = true, distanceMeters = 0f, accuracyMeters = 1f, radiusMeters = 50f))
    }

    @Test
    fun impreciseFix_isRejectedRatherThanTrusted() {
        // A 2 km cell-tower fix would "overlap" almost any fence; it must not unlock files.
        assertEquals(Result.INACCURATE, FenceCheck.classify(isMock = false, distanceMeters = 0f, accuracyMeters = 2000f, radiusMeters = 50f))
    }

    @Test
    fun safeFileName_keepsReadableNames() {
        assertEquals("Holiday photo 01.jpg", safeFileName("Holiday photo 01.jpg"))
    }

    @Test
    fun safeFileName_neverProducesAPath() {
        for (evil in listOf("../../shared_prefs/x.xml", "..", "/etc/passwd", "a/b\\c", "....")) {
            val safe = safeFileName(evil)
            assertFalse("'$evil' -> '$safe'", safe.contains('/') || safe.contains('\\') || safe.startsWith(".") || safe.isEmpty())
        }
    }
}
