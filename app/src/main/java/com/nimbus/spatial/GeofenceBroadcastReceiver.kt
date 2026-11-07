package com.nimbus.spatial

import android.Manifest
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
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/** Receives ENTER/EXIT transitions from Play Services. */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e(TAG, "Geofence error: ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                // Location was turned off: Android drops all fences, so whatever we thought we were
                // inside is stale. They're re-registered the next time the app opens.
                GeofenceStateStore.clear(context)
            }
            return
        }

        val ids = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        if (ids.isEmpty()) return
        val entered = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> true
            Geofence.GEOFENCE_TRANSITION_EXIT -> false
            else -> return
        }

        // Network and wallpaper work must not run on the main thread, and must finish before
        // Android is allowed to kill the process, hence goAsync() plus a worker thread.
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                handleTransition(appContext, ids, entered)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle geofence transition", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handleTransition(context: Context, ids: List<String>, entered: Boolean) {
        val inside = if (entered) {
            GeofenceStateStore.onEnter(context, ids)
        } else {
            GeofenceStateStore.onExit(context, ids)
        }
        Log.d(TAG, "${if (entered) "Entered" else "Exited"} $ids; inside ${inside.size} fence(s)")

        // Based on all fences, so leaving one of two overlapping fences keeps the "inside" wallpaper.
        WallpaperUtils.setWallpaper(context, if (inside.isEmpty()) R.drawable.out_fence else R.drawable.in_fence)

        for (id in ids) {
            val name = fetchAreaName(id) ?: "a saved place"
            showNotification(context, id, if (entered) "You are in $name" else "You are out of $name")
        }
    }

    /** Blocking; only call off the main thread. Null when offline or the area no longer exists. */
    private fun fetchAreaName(areaId: String): String? = try {
        Tasks.await(GeofenceRepository.areaName(areaId), 5, TimeUnit.SECONDS)
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't fetch name for $areaId: ${e.message}")
        null
    }

    private fun showNotification(context: Context, areaId: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notification permission not granted")
            return
        }

        val channel = NotificationChannel(CHANNEL_ID, "North Geofence Alerts", NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_northstar)
            .setContentTitle("North")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // One notification per fence: an EXIT replaces that fence's ENTER instead of stacking.
        NotificationManagerCompat.from(context).notify(areaId.hashCode(), notification)
    }

    private companion object {
        const val TAG = "GeofenceReceiver"
        const val CHANNEL_ID = "nimbus_geofence_channel"
    }
}
