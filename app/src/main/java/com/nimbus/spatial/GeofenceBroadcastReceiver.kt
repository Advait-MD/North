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
import com.google.android.gms.location.GeofencingEvent
import com.google.firebase.firestore.FirebaseFirestore

const val PREF_NAME = "GeofencePrefs"
const val KEY_CURRENT_FENCE_ID = "currentFenceId"

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null || geofencingEvent.hasError()) {
            Log.e("GeofenceReceiver", "Geofence error: ${geofencingEvent?.errorCode}")
            return
        }

        val transitionType = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: run {
            Log.e("GeofenceReceiver", "No triggering geofences found")
            return
        }

        if (triggeringGeofences.isEmpty()) return
        val triggeringIds = triggeringGeofences.map { it.requestId }
        val areaId = triggeringIds[0]
        Log.d("GeofenceReceiver", "Transition $transitionType for geofences: $triggeringIds")

        // Persisted set of fences the user is inside (survives process death, no duplicates).
        when (transitionType) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                val inside = GeofenceStateStore.onEnter(context, triggeringIds)
                Log.d("GeofenceReceiver", "Entered $triggeringIds, inside: $inside")
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                val inside = GeofenceStateStore.onExit(context, triggeringIds)
                Log.d("GeofenceReceiver", "Exited $triggeringIds, inside: $inside")
            }
            else -> {
                Log.d("GeofenceReceiver", "Unhandled transition type: $transitionType")
                return
            }
        }

        // Existing logic for SharedPreferences and notifications
        FirebaseFirestore.getInstance()
            .collection("geofence_areas")
            .document(areaId)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val areaName = doc.getString("name") ?: "Unknown"
                    val sharedPref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

                    val message = when (transitionType) {
                        Geofence.GEOFENCE_TRANSITION_ENTER -> {
                            WallpaperUtils.setWallpaper(context, R.drawable.in_fence)
                            sharedPref.edit().putString(KEY_CURRENT_FENCE_ID, areaId).apply()
                            "You are in $areaName"
                        }
                        Geofence.GEOFENCE_TRANSITION_EXIT -> {
                            WallpaperUtils.setWallpaper(context, R.drawable.out_fence)
                            sharedPref.edit().remove(KEY_CURRENT_FENCE_ID).apply()
                            "You are out of $areaName"
                        }
                        else -> return@addOnSuccessListener
                    }

                    showNotification(context, message)
                }
            }
            .addOnFailureListener {
                Log.e("GeofenceReceiver", "Failed to fetch area name: ${it.message}")
            }
    }

    private fun showNotification(context: Context, message: String) {
        val channelId = "nimbus_geofence_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "North Geofence Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_northstar)
            .setContentTitle("North")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationId = (1000..999999).random()

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } else {
            Log.e("GeofenceReceiver", "Notification permission not granted")
        }
    }
}