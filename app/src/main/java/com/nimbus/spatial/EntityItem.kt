package com.nimbus.spatial

/** A file attached to a geofence. [uri] is an on-device content:// URI. */
data class EntityItem(
    val fileName: String,
    val uri: String,
    val mimeType: String
)
