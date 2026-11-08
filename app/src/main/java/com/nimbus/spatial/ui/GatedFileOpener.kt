package com.nimbus.spatial.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.CancellationTokenSource
import com.nimbus.spatial.data.AttachedFile
import com.nimbus.spatial.data.GeofenceArea
import com.nimbus.spatial.geofence.GeofenceStateStore
import com.nimbus.spatial.security.FenceCheck
import com.nimbus.spatial.security.FileVault
import com.nimbus.spatial.security.LiveLocationGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens an attached file only after both security gates pass:
 *
 *  1. Saved state: geofence events say the user is inside this fence (fast, can be stale).
 *  2. Live location: a fresh, non-mock fix inside the fence *as pinned on this phone* when its
 *     first file was attached (so editing or recreating the fence in Firestore doesn't help).
 *
 * Only then is the file decrypted to a temporary copy and handed to a viewer app.
 */
class GatedFileOpener(
    private val activity: AppCompatActivity,
    private val vault: FileVault,
    private val fence: GeofenceArea
) {
    private val cancellation = CancellationTokenSource()

    /** Call from onDestroy so a pending location fix doesn't call back into a dead screen. */
    fun cancel() = cancellation.cancel()

    fun open(file: AttachedFile) {
        // Gate 1
        if (!GeofenceStateStore.isInside(activity, fence.id)) {
            activity.toast("You're outside the geofence")
            return
        }
        activity.toast("Checking your location...")
        activity.lifecycleScope.launch {
            val pinned = try {
                withContext(Dispatchers.IO) { vault.pinnedFence(fence) }
            } catch (e: Exception) { // GCM check failed: the record was tampered with or damaged
                activity.toast("This fence's location record is damaged. Files stay locked.", long = true)
                return@launch
            }
            // Gate 2. A fence with no pin has never had an encrypted file, so Firestore's copy is fine.
            LiveLocationGate.check(activity, pinned ?: fence, cancellation.token) { result ->
                when (result) {
                    FenceCheck.Result.INSIDE -> decryptAndOpen(file)
                    FenceCheck.Result.OUTSIDE -> activity.toast("Your current location is outside this geofence")
                    FenceCheck.Result.MOCK_LOCATION -> activity.toast("Blocked: a mock location app is active", long = true)
                    FenceCheck.Result.INACCURATE -> activity.toast("Your location is too imprecise right now. Try again.")
                    FenceCheck.Result.NO_FIX -> activity.toast("Couldn't get your location. Is location turned on?")
                }
            }
        }
    }

    private fun decryptAndOpen(file: AttachedFile) {
        val legacyUri = file.legacyUri
        if (legacyUri != null) { // attached before encryption existed: open the original directly
            openInViewer(Uri.parse(legacyUri), file.mimeType)
            return
        }
        activity.lifecycleScope.launch {
            val plainUri = try {
                withContext(Dispatchers.IO) { vault.decryptForViewing(fence.id, file.id, file.name) }
            } catch (e: Exception) {
                activity.toast("Couldn't open ${file.name}: ${e.message}", long = true)
                return@launch
            }
            openInViewer(plainUri, file.mimeType)
        }
    }

    private fun openInViewer(uri: Uri, mimeType: String) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // read access to this one file only
        try {
            activity.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            activity.toast("No app to open this file type")
        } catch (e: SecurityException) { // only legacy, unencrypted files: original moved or deleted
            activity.toast("North can't open this file any more. Long-press it to remove it.", long = true)
        }
    }
}
