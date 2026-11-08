package com.nimbus.spatial.geofence

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Keeps a low-power location request running while the app is in use. Nothing reads the results:
 * the open request alone makes Play Services deliver geofence events sooner (in the background
 * alone they can arrive minutes late).
 */
class LocationService : Service() {

    private lateinit var locationClient: FusedLocationProviderClient

    private val ignoreResults = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        if (startInForeground()) requestLocationUpdates()
    }

    // Not sticky: Android 12+ refuses to restart a location service from the background.
    // MainActivity starts it again whenever the app is opened.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun startInForeground(): Boolean = try {
        // Android 14+ requires the service type, and throws if location permission is missing.
        ServiceCompat.startForeground(
            this,
            Notifications.SERVICE_NOTIFICATION_ID,
            Notifications.locationServiceNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        true
    } catch (e: Exception) {
        Log.e("LocationService", "Can't start in the foreground: ${e.message}")
        stopSelf()
        false
    }

    private fun requestLocationUpdates() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        // Wi-Fi/cell accuracy every 30 seconds: keeps fences responsive without holding GPS on.
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L).build()
        locationClient.requestLocationUpdates(request, ignoreResults, mainLooper)
    }

    override fun onDestroy() {
        locationClient.removeLocationUpdates(ignoreResults)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
