package com.nimbus.spatial.security

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationToken
import com.nimbus.spatial.data.GeofenceArea

/**
 * Second gate before a file is decrypted. The saved inside/outside state comes from geofence
 * events, which can be stale; this takes a fresh fix right now and rejects mock locations.
 *
 * Not bulletproof: a rooted phone can hide a mock location from apps.
 */
object LiveLocationGate {

    @SuppressLint("MissingPermission") // checked below
    fun check(
        context: Context,
        fence: GeofenceArea,
        cancellation: CancellationToken,
        onResult: (FenceCheck.Result) -> Unit
    ) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted || fence.radius <= 0f) {
            onResult(FenceCheck.Result.NO_FIX)
            return
        }

        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation)
            .addOnSuccessListener { location ->
                if (location == null) {
                    onResult(FenceCheck.Result.NO_FIX)
                    return@addOnSuccessListener
                }
                val distance = FloatArray(1)
                Location.distanceBetween(location.latitude, location.longitude, fence.latitude, fence.longitude, distance)
                onResult(FenceCheck.classify(isMock(location), distance[0], location.accuracy, fence.radius))
            }
            .addOnFailureListener { onResult(FenceCheck.Result.NO_FIX) }
    }

    @Suppress("DEPRECATION") // isFromMockProvider is the only option below Android 12
    private fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider
}
