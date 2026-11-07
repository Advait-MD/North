package com.nimbus.spatial

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot

/**
 * Finds the user's own places plus public places nearby (geohash query), and keeps the
 * nearest [GeoMath.MAX_GEOFENCES] of them registered with Android's geofencing API.
 */
object PlaceSync {
    private const val TAG = "PlaceSync"

    /** How far around the user to look for public places. */
    const val SEARCH_RADIUS_M = 5_000.0
    private const val RESYNC_DISTANCE_M = 1_000.0
    private const val RESYNC_INTERVAL_MS = 15 * 60 * 1000L

    data class Result(val mine: List<GeofenceArea>, val nearbyPublic: List<GeofenceArea>)

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext, 0,
        Intent(context.applicationContext, GeofenceBroadcastReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    fun shouldRefresh(context: Context, lat: Double, lng: Double, now: Long = System.currentTimeMillis()): Boolean {
        val (lastLat, lastLng, at) = GeofenceStateStore.lastSync(context) ?: return true
        return now - at > RESYNC_INTERVAL_MS ||
            GeoMath.distanceMeters(lat, lng, lastLat, lastLng) > RESYNC_DISTANCE_M
    }

    fun refresh(
        context: Context, lat: Double, lng: Double,
        onResult: (Result) -> Unit = {}, onError: (Exception) -> Unit = {}
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val appContext = context.applicationContext
        val places = FirebaseFirestore.getInstance().collection(FirestorePaths.PLACES)
        val mineTask = places.whereEqualTo("ownerId", uid).get()
        val publicTasks = GeoFireUtils
            .getGeoHashQueryBounds(GeoLocation(lat, lng), SEARCH_RADIUS_M)
            .map { b ->
                places.whereEqualTo("visibility", Visibility.PUBLIC)
                    .orderBy("geohash").startAt(b.startHash).endAt(b.endHash)
                    .get()
            }

        Tasks.whenAllComplete(listOf(mineTask) + publicTasks).addOnCompleteListener {
            if (!mineTask.isSuccessful) {
                val e = mineTask.exception ?: Exception("Failed to load your places")
                Log.e(TAG, "Loading own places failed", e)
                // Leave the current registrations alone rather than dropping them all.
                onError(e)
                return@addOnCompleteListener
            }
            GeofenceStateStore.setLastSync(appContext, lat, lng, System.currentTimeMillis())
            val mine = mineTask.result.toPlaces()
            val mineIds = mine.map { it.id }.toSet()

            // Geohash bounds over-select; drop corners outside the search circle.
            val nearbyPublic = publicTasks
                .filter { t ->
                    if (!t.isSuccessful) Log.e(TAG, "Public place query failed", t.exception)
                    t.isSuccessful
                }
                .flatMap { it.result.toPlaces() }
                .distinctBy { it.id }
                .filter { it.id !in mineIds && it.enabled }
                .filter { GeoMath.distanceMeters(lat, lng, it.latitude, it.longitude) <= SEARCH_RADIUS_M }
                .sortedBy { GeoMath.distanceMeters(lat, lng, it.latitude, it.longitude) }

            val candidates = mine.filter { it.enabled } + nearbyPublic
            val toWatch = GeoMath.nearest(candidates, lat, lng) { it.latitude to it.longitude }
            register(appContext, toWatch)
            onResult(Result(mine, nearbyPublic))
        }
    }

    /** Registers [places] and unregisters anything else, touching only what changed. */
    @SuppressLint("MissingPermission")
    private fun register(context: Context, places: List<GeofenceArea>) {
        val client = LocationServices.getGeofencingClient(context)
        val wanted = places.associateBy { it.id }
        val registered = GeofenceStateStore.registeredIds(context)

        val stale = registered - wanted.keys
        if (stale.isNotEmpty()) {
            client.removeGeofences(stale.toList())
            stale.forEach { GeofenceStateStore.markOutside(context, it) }
        }

        val fresh = wanted.keys - registered
        if (fresh.isEmpty()) {
            GeofenceStateStore.setRegisteredIds(context, wanted.keys)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "No location permission; cannot register geofences")
            return
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(fresh.map { id ->
                val p = wanted.getValue(id)
                Geofence.Builder()
                    .setRequestId(p.id)
                    .setCircularRegion(p.latitude, p.longitude, p.radius)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            })
            .build()
        try {
            client.addGeofences(request, pendingIntent(context))
                .addOnSuccessListener {
                    GeofenceStateStore.setRegisteredIds(context, (registered - stale) + fresh)
                    Log.d(TAG, "Watching ${wanted.size} places (${fresh.size} new, ${stale.size} dropped)")
                }
                .addOnFailureListener { Log.e(TAG, "addGeofences failed: ${it.message}") }
        } catch (e: SecurityException) {
            Log.e(TAG, "addGeofences denied", e)
        }
    }

    /** Stop watching one place, e.g. after it is deleted. */
    fun unregister(context: Context, id: String) {
        LocationServices.getGeofencingClient(context).removeGeofences(listOf(id))
        GeofenceStateStore.setRegisteredIds(context, GeofenceStateStore.registeredIds(context) - id)
        GeofenceStateStore.markOutside(context, id)
    }

    private fun QuerySnapshot.toPlaces() = documents.mapNotNull { GeofenceArea.fromDoc(it) }
}
