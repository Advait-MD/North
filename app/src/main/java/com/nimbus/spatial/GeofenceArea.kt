package com.nimbus.spatial

import com.google.firebase.firestore.PropertyName

data class GeofenceArea(
    val id: String = "",
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radius: Float = 0f,
    // Without this, Firestore maps the getter isEnabled() to a field named "enabled".
    // Older documents were saved that way; MainActivity reads both names.
    @get:PropertyName("isEnabled")
    val isEnabled: Boolean = true,
    val ownerId: String = ""
)
