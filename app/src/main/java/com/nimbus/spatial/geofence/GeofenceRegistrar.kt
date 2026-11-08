package com.nimbus.spatial.geofence

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
import com.nimbus.spatial.data.GeofenceArea

/** Adds and removes geofences with Play Services. Used by MainActivity and GeofenceBootReceiver. */
object GeofenceRegistrar {
    private const val TAG = "GeofenceRegistrar"
    private const val MAX_GEOFENCES = 100 // Play Services' limit per app

    /**
     * Geofencing needs precise location, and on Android 10+ also "Allow all the time"
     * (background location). Without both, Play Services rejects the fences.
     */
    fun canRegister(context: Context): Boolean {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        val backgroundOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return granted(Manifest.permission.ACCESS_FINE_LOCATION) && backgroundOk
    }

    /**
     * Registers [areas] with Play Services. Safe to call repeatedly: a fence with the same ID
     * replaces the old one. [onDone] runs when registration has finished, whether or not it worked.
     */
    @SuppressLint("MissingPermission") // checked by canRegister()
    fun register(context: Context, areas: List<GeofenceArea>, onDone: () -> Unit = {}) {
        val valid = areas.filter { it.id.isNotEmpty() && it.radius > 0f }.take(MAX_GEOFENCES)
        if (valid.isEmpty() || !canRegister(context)) {
            if (valid.isNotEmpty()) Log.w(TAG, "Location permission missing; fences not registered")
            onDone()
            return
        }

        val request = GeofencingRequest.Builder()
            // Fire ENTER straight away for fences the user is already inside.
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(valid.map(::toGeofence))
            .build()

        try {
            LocationServices.getGeofencingClient(context)
                .addGeofences(request, pendingIntent(context))
                .addOnFailureListener { Log.e(TAG, "Registering fences failed: ${it.message}") }
                .addOnCompleteListener { onDone() }
        } catch (e: SecurityException) { // permission revoked a moment ago
            Log.e(TAG, "Registering fences failed", e)
            onDone()
        }
    }

    fun remove(context: Context, areaId: String) {
        LocationServices.getGeofencingClient(context).removeGeofences(listOf(areaId))
    }

    fun removeAll(context: Context) {
        LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
    }

    private fun toGeofence(area: GeofenceArea): Geofence =
        Geofence.Builder()
            .setRequestId(area.id)
            .setCircularRegion(area.latitude, area.longitude, area.radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

    /** Where Play Services delivers events. Mutable because Play Services fills in the event details. */
    private fun pendingIntent(context: Context): PendingIntent {
        val appContext = context.applicationContext
        return PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(appContext, GeofenceBroadcastReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}
