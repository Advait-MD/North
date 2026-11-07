package com.nimbus.spatial

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Keeps a low-power location request alive while the app runs. The results aren't used directly:
 * an active request makes Play Services' geofence transitions arrive faster than relying on
 * background location alone (which can lag by minutes).
 */
class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Intentionally empty: the request itself is what keeps geofencing responsive.
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (startInForeground()) requestLocationUpdates()
    }

    // Not sticky: a restart from the background would be refused on Android 12+.
    // MainActivity starts the service again whenever the app is opened.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun startInForeground(): Boolean {
        val channel = NotificationChannel(CHANNEL_ID, "Location Updates", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("North Point")
            .setContentText("Getting along with your space...")
            .setSmallIcon(R.drawable.ic_northstar)
            .build()

        return try {
            // Android 14+ requires the type, and throws if location permission isn't granted yet.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            true
        } catch (e: Exception) { // SecurityException, or ForegroundServiceStartNotAllowedException
            Log.e(TAG, "Can't start location foreground service: ${e.message}")
            stopSelf()
            false
        }
    }

    private fun requestLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        // Balanced power (Wi-Fi/cell) every 30s: enough to keep fences responsive without
        // holding GPS on continuously.
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L).build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val TAG = "LocationService"
        const val CHANNEL_ID = "location_channel"
        const val NOTIFICATION_ID = 1
    }
}
