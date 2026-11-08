package com.nimbus.spatial.data

/**
 * A file on this phone that the user attached to a geofence.
 * Stored in Firestore at `geofences/{areaId}/entities/{id}`; the file itself never leaves the phone.
 */
data class AttachedFile(
    val id: String, // Firestore document ID; empty until saved
    val name: String,
    val uri: String, // content:// URI, only valid on this device
    val mimeType: String
)
