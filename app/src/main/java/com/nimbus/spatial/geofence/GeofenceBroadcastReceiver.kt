package com.nimbus.spatial.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.tasks.Tasks
import com.nimbus.spatial.R
import com.nimbus.spatial.data.GeofenceRepository
import com.nimbus.spatial.security.FileVault
import java.util.concurrent.TimeUnit

/** Receives ENTER/EXIT events from Play Services: updates the saved state, wallpaper and notifications. */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e(TAG, "Geofence error: ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
            if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                // Location was turned off and Android dropped every fence, so the saved state is stale.
                // MainActivity re-registers the fences the next time the app opens.
                GeofenceStateStore.clear(context)
                FileVault.clearDecrypted(context)
            }
            return
        }

        val ids = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        val entered = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> true
            Geofence.GEOFENCE_TRANSITION_EXIT -> false
            else -> return
        }
        if (ids.isEmpty()) return

        // Network lookups and decoding the wallpaper are too slow for the main thread.
        // goAsync() keeps the process alive until finish() is called.
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
        val change = if (entered) GeofenceStateStore.enter(context, ids) else GeofenceStateStore.exit(context, ids)

        // Fences are re-registered on every app launch and after a reboot, and Play Services then
        // repeats ENTER for fences the user is already inside. Those repeats change nothing.
        if (change.changedIds.isEmpty()) return

        // Leaving a fence wipes any of its files that are decrypted for a viewer app right now.
        if (!entered) change.changedIds.forEach { FileVault.clearDecrypted(context, it) }

        if (change.insideAnyChanged) {
            Wallpaper.set(context, if (change.after.isEmpty()) R.drawable.out_fence else R.drawable.in_fence)
        }
        for (id in change.changedIds) {
            val name = fetchAreaName(id) ?: "a saved place"
            Notifications.showGeofenceAlert(context, id, if (entered) "You are in $name" else "You are out of $name")
        }
    }

    /** Blocks for up to 5 seconds, so only call it off the main thread. Null if offline or deleted. */
    private fun fetchAreaName(areaId: String): String? = try {
        Tasks.await(GeofenceRepository.areaName(areaId), 5, TimeUnit.SECONDS)
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't fetch the name of $areaId: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "GeofenceReceiver"
    }
}
