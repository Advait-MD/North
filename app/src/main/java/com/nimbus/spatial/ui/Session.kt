package com.nimbus.spatial.ui

import android.app.Activity
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth
import com.nimbus.spatial.geofence.GeofenceRegistrar
import com.nimbus.spatial.geofence.GeofenceStateStore
import com.nimbus.spatial.geofence.LocationService
import com.nimbus.spatial.security.FileVault

/** Signing out. Encrypted files stay on the phone, so signing back in brings them back. */
object Session {

    fun logOut(activity: Activity) {
        // The next account on this phone must not inherit this user's fences or saved state.
        GeofenceRegistrar.removeAll(activity)
        GeofenceStateStore.clear(activity)
        FileVault.clearDecrypted(activity)
        activity.stopService(Intent(activity, LocationService::class.java))
        FirebaseAuth.getInstance().signOut()

        // Clear the back stack so Back can't return to a signed-in screen.
        activity.startActivity(
            Intent(activity, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }
}
