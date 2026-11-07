package com.nimbus.spatial

import com.google.firebase.firestore.DocumentSnapshot

object Visibility {
    const val PUBLIC = "public"
    const val PRIVATE = "private"
}

/** Firestore layout: geofence_areas/{placeId}/entities/{fileId}. */
object FirestorePaths {
    const val PLACES = "geofence_areas"
    const val FILES = "entities"
    fun storagePath(placeId: String, fileId: String) = "places/$placeId/$fileId"
}

/**
 * A place ("geofence") that files are pinned to.
 *
 * Mapped by hand instead of with toObject(): a Kotlin `val isEnabled` is written by
 * Firestore's bean mapper as "enabled" and then never read back, and `val`s have no
 * setters for the mapper to use.
 */
data class GeofenceArea(
    val id: String = "",
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radius: Float = 0f,
    val enabled: Boolean = true,
    val ownerId: String = "",
    val visibility: String = Visibility.PRIVATE,
    val geohash: String = "",
    val createdAt: Long = 0L
) {
    val isPublic: Boolean get() = visibility == Visibility.PUBLIC

    fun toMap(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "latitude" to latitude,
        "longitude" to longitude,
        "radius" to radius.toDouble(),
        "enabled" to enabled,
        "ownerId" to ownerId,
        "visibility" to visibility,
        "geohash" to geohash,
        "createdAt" to createdAt
    )

    companion object {
        fun fromDoc(doc: DocumentSnapshot): GeofenceArea? {
            if (!doc.exists()) return null
            return GeofenceArea(
                id = doc.id,
                name = doc.getString("name") ?: "Unnamed place",
                latitude = doc.getDouble("latitude") ?: return null,
                longitude = doc.getDouble("longitude") ?: return null,
                radius = (doc.getDouble("radius") ?: return null).toFloat(),
                // Older documents were written as "enabled" by the bean mapper.
                enabled = doc.getBoolean("enabled") ?: doc.getBoolean("isEnabled") ?: true,
                ownerId = doc.getString("ownerId") ?: "",
                visibility = doc.getString("visibility") ?: Visibility.PRIVATE,
                geohash = doc.getString("geohash") ?: "",
                createdAt = doc.getLong("createdAt") ?: 0L
            )
        }
    }
}
