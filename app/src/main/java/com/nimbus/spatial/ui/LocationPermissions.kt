package com.nimbus.spatial.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nimbus.spatial.geofence.GeofenceRegistrar

/**
 * The two-step location permission flow.
 *
 * Android 11+ ignores a request that asks for foreground and background location together
 * (it grants neither), so foreground location is requested first and "Allow all the time" second.
 *
 * Create it as a property of the activity: result launchers must be registered before onStart().
 */
class LocationPermissions(
    private val activity: AppCompatActivity,
    private val onForegroundGranted: () -> Unit,
    private val onBackgroundGranted: () -> Unit
) {
    private val foregroundRequest =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasForeground()) onForegroundGranted()
            else activity.toast("Location permission is needed to create geofences", long = true)
        }

    private val backgroundRequest =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onBackgroundGranted()
            else activity.toast("Geofences won't trigger without \"Allow all the time\"", long = true)
        }

    fun hasForeground(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Asks for location (and notifications on Android 13+). */
    fun requestForeground() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        foregroundRequest.launch(permissions.toTypedArray())
    }

    /** Explains why, then asks for "Allow all the time". Does nothing if it's already granted. */
    fun requestBackgroundIfNeeded() {
        if (GeofenceRegistrar.canRegister(activity)) return
        AlertDialog.Builder(activity)
            .setTitle("Allow location all the time")
            .setMessage(
                "North unlocks your files when you enter a geofence, even while the app is closed. " +
                    "On the next screen, choose \"Allow all the time\"."
            )
            .setPositiveButton("Continue") { _, _ ->
                backgroundRequest.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton("Not now", null)
            .show()
    }
}
