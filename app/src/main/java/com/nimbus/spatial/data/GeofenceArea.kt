package com.nimbus.spatial.data

import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * A circular area the user saved. Stored in Firestore at `geofence_areas/{id}`.
 * The default values give Firestore the no-argument constructor that toObject() needs.
 */
@IgnoreExtraProperties // older documents also carry an unused "isEnabled"/"enabled" flag
data class GeofenceArea(
    val id: String = "",
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radius: Float = 0f, // meters
    val ownerId: String = ""
)
