package com.nimbus.spatial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.auth.FirebaseAuth

/** Android clears all geofences on reboot. This re-registers the signed-in user's fences. */
class GeofenceBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val appContext = context.applicationContext
        // The user may have moved while the phone was off. INITIAL_TRIGGER_ENTER re-adds
        // whichever fences they're actually inside once registration completes.
        GeofenceStateStore.clear(appContext)

        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!GeofenceRegistrar.hasRequiredPermissions(appContext)) return

        val pending = goAsync()
        GeofenceRepository.loadAreas(userId)
            .addOnSuccessListener { areas ->
                GeofenceRegistrar.register(appContext, areas) { pending.finish() }
            }
            .addOnFailureListener {
                Log.e("GeofenceBootReceiver", "Couldn't load geofences after boot: ${it.message}")
                pending.finish()
            }
    }
}
