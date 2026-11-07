package com.nimbus.spatial

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e(TAG, "Geofence error: ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                // Location was switched off and Android dropped every geofence.
                GeofenceStateStore.resetRegistration(context)
            }
            return
        }

        val transition = event.geofenceTransition
        val ids = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        if (ids.isEmpty()) return

        val changed = when (transition) {
            // markInside() is false when we already knew, e.g. after a re-registration.
            Geofence.GEOFENCE_TRANSITION_ENTER -> ids.filter { GeofenceStateStore.markInside(context, it) }
            Geofence.GEOFENCE_TRANSITION_EXIT -> ids.onEach { GeofenceStateStore.markOutside(context, it) }
            else -> return
        }
        if (changed.isEmpty()) return

        // Firestore lookups outlive onReceive(), so keep the receiver alive until they finish.
        val pending = goAsync()
        var remaining = changed.size
        fun done() { if (--remaining == 0) pending.finish() }

        changed.forEach { id ->
            PlaceRepository.getPlace(id).addOnCompleteListener { task ->
                // task.result throws on failure, so check first.
                val name = (if (task.isSuccessful) task.result?.name else null) ?: "a North place"
                val entered = transition == Geofence.GEOFENCE_TRANSITION_ENTER
                showNotification(context, if (entered) "You are in $name" else "You left $name")
                done()
            }
        }

        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            WallpaperUtils.setWallpaper(context, R.drawable.in_fence)
        } else if (!GeofenceStateStore.isInsideAny(context)) {
            WallpaperUtils.setWallpaper(context, R.drawable.out_fence)
        }
    }

    private fun showNotification(context: Context, message: String) {
        val channelId = "nimbus_geofence_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "North Geofence Alerts", NotificationManager.IMPORTANCE_HIGH
            )
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_northstar)
            .setContentTitle("North")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify((1000..999999).random(), builder.build())
        } else {
            Log.w(TAG, "Notification permission not granted")
        }
    }

    private companion object {
        const val TAG = "GeofenceReceiver"
    }
}
