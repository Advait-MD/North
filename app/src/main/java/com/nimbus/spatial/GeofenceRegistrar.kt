package com.nimbus.spatial

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/** Registers and removes geofences with Play Services. Used by MainActivity and the boot receiver. */
object GeofenceRegistrar {
    private const val TAG = "GeofenceRegistrar"
    private const val MAX_GEOFENCES = 100 // Play Services limit per app

    fun pendingIntent(context: Context): PendingIntent {
        val appContext = context.applicationContext
        return PendingIntent.getBroadcast(
            appContext, 0, Intent(appContext, GeofenceBroadcastReceiver::class.java),
            // Must be mutable: Play Services fills in the transition details.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    /** On Android 10+ the Geofencing API rejects fences unless background location is granted. */
    fun hasRequiredPermissions(context: Context): Boolean {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }

    /**
     * Registers the enabled areas. Safe to call repeatedly: the same request IDs replace existing
     * fences, and the repeated initial ENTER is harmless because inside-state is a set.
     * [onDone] runs once registration has finished, succeeded or not.
     */
    @SuppressLint("MissingPermission") // checked by hasRequiredPermissions()
    fun register(context: Context, areas: List<GeofenceArea>, onDone: () -> Unit = {}) {
        val valid = areas.filter { it.isEnabled && it.id.isNotEmpty() && it.radius > 0f }
        if (valid.isEmpty()) return onDone()
        if (!hasRequiredPermissions(context)) {
            Log.w(TAG, "Location permission missing; not registering ${valid.size} geofence(s)")
            return onDone()
        }
        if (valid.size > MAX_GEOFENCES) {
            Log.w(TAG, "Only the first $MAX_GEOFENCES of ${valid.size} geofences can be registered")
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(valid.take(MAX_GEOFENCES).map { area ->
                Geofence.Builder()
                    .setRequestId(area.id)
                    .setCircularRegion(area.latitude, area.longitude, area.radius)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            })
            .build()

        try {
            LocationServices.getGeofencingClient(context)
                .addGeofences(request, pendingIntent(context))
                .addOnSuccessListener { Log.d(TAG, "Registered ${valid.size} geofence(s)") }
                .addOnFailureListener { Log.e(TAG, "Registering geofences failed: ${it.message}") }
                .addOnCompleteListener { onDone() }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission revoked while registering geofences", e)
            onDone()
        }
    }

    fun remove(context: Context, areaId: String) {
        LocationServices.getGeofencingClient(context).removeGeofences(listOf(areaId))
            .addOnFailureListener { Log.e(TAG, "Removing geofence $areaId failed: ${it.message}") }
    }

    fun removeAll(context: Context) {
        LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
            .addOnFailureListener { Log.e(TAG, "Removing geofences failed: ${it.message}") }
    }
}
