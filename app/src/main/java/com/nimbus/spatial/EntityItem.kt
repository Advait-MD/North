package com.nimbus.spatial

import com.google.firebase.firestore.DocumentSnapshot

/** A file pinned to a place. The bytes live in Firebase Storage at [storagePath]. */
data class PlaceFile(
    val id: String = "",
    val placeId: String = "",
    val displayName: String = "",
    val storagePath: String = "",
    val mimeType: String = "application/octet-stream",
    val sizeBytes: Long = 0L,
    val visibility: String = Visibility.PRIVATE,
    val ownerId: String = "",
    val createdAt: Long = 0L
) {
    val isPublic: Boolean get() = visibility == Visibility.PUBLIC

    fun toMap(): Map<String, Any> = mapOf(
        "placeId" to placeId,
        "displayName" to displayName,
        "storagePath" to storagePath,
        "mimeType" to mimeType,
        "sizeBytes" to sizeBytes,
        "visibility" to visibility,
        "ownerId" to ownerId,
        "createdAt" to createdAt
    )

    companion object {
        fun fromDoc(placeId: String, doc: DocumentSnapshot): PlaceFile? {
            // Files saved before uploads existed only had a device-local URI: unusable, skip them.
            val path = doc.getString("storagePath") ?: return null
            return PlaceFile(
                id = doc.id,
                placeId = placeId,
                displayName = doc.getString("displayName") ?: "Unnamed file",
                storagePath = path,
                mimeType = doc.getString("mimeType") ?: "application/octet-stream",
                sizeBytes = doc.getLong("sizeBytes") ?: 0L,
                visibility = doc.getString("visibility") ?: Visibility.PRIVATE,
                ownerId = doc.getString("ownerId") ?: "",
                createdAt = doc.getLong("createdAt") ?: 0L
            )
        }
    }
}

/** What the file list shows: a file plus whether this viewer may open it right now. */
data class EntityItem(
    val file: PlaceFile,
    val isAccessible: Boolean,
    val isMine: Boolean
)
