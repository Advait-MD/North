package com.nimbus.spatial.data

/**
 * A file attached to a geofence. Firestore stores only its metadata at
 * `geofences/{areaId}/entities/{id}`; the encrypted contents are in FileVault on this phone.
 */
data class AttachedFile(
    val id: String, // Firestore document ID, also the encrypted file's name in FileVault
    val name: String,
    val mimeType: String,
    /**
     * Set only for files attached before encryption existed: a plain content:// URI to the
     * unencrypted original. Null for every encrypted file.
     */
    val legacyUri: String? = null
) {
    val isEncrypted: Boolean get() = legacyUri == null
}
