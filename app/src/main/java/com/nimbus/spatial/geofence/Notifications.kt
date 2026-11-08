package com.nimbus.spatial.geofence

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nimbus.spatial.R

/** Builds the app's two notifications: geofence alerts, and the location service's ongoing one. */
object Notifications {
    private const val ALERT_CHANNEL_ID = "nimbus_geofence_channel"
    private const val SERVICE_CHANNEL_ID = "location_channel"
    const val SERVICE_NOTIFICATION_ID = 1

    /** One notification per fence: leaving replaces that fence's "You are in" instead of stacking. */
    fun showGeofenceAlert(context: Context, areaId: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("Notifications", "Notification permission not granted")
            return
        }
        createChannel(context, ALERT_CHANNEL_ID, "North Geofence Alerts", NotificationManager.IMPORTANCE_HIGH)
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_northstar)
            .setContentTitle("North")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(areaId.hashCode(), notification)
    }

    fun locationServiceNotification(context: Context): Notification {
        createChannel(context, SERVICE_CHANNEL_ID, "Location Updates", NotificationManager.IMPORTANCE_LOW)
        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_northstar)
            .setContentTitle("North Point")
            .setContentText("Getting along with your space...")
            .build()
    }

    // Creating a channel that already exists does nothing, so this is safe to call every time.
    private fun createChannel(context: Context, id: String, name: String, importance: Int) {
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(NotificationChannel(id, name, importance))
    }
}
