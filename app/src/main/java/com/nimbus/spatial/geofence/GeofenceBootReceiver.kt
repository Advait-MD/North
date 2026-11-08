package com.nimbus.spatial.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.nimbus.spatial.data.GeofenceRepository

/** Android deletes every geofence on reboot. This puts the signed-in user's fences back. */
class GeofenceBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val appContext = context.applicationContext

        // The user may have moved while the phone was off. Once the fences are registered,
        // Play Services sends ENTER for the ones they're actually inside.
        GeofenceStateStore.clear(appContext)

        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!GeofenceRegistrar.canRegister(appContext)) return

        val pending = goAsync() // keep the process alive until the fences are registered
        GeofenceRepository.loadAreas(userId)
            .addOnSuccessListener { areas -> GeofenceRegistrar.register(appContext, areas) { pending.finish() } }
            .addOnFailureListener {
                Log.e("GeofenceBootReceiver", "Couldn't load fences after boot: ${it.message}")
                pending.finish()
            }
    }
}
