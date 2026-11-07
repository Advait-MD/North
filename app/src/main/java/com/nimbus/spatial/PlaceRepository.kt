package com.nimbus.spatial

import android.net.Uri
import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata

/** All reads and writes for places and their files. */
object PlaceRepository {
    /** Mirrors the limit in storage.rules. */
    const val MAX_UPLOAD_BYTES = 50L * 1024 * 1024

    private val db get() = FirebaseFirestore.getInstance()
    private val storage get() = FirebaseStorage.getInstance()
    private val functions get() = FirebaseFunctions.getInstance()

    private fun places() = db.collection(FirestorePaths.PLACES)
    private fun files(placeId: String) =
        places().document(placeId).collection(FirestorePaths.FILES)

    fun newPlace(
        name: String, lat: Double, lng: Double, radius: Float, ownerId: String, isPublic: Boolean
    ) = GeofenceArea(
        id = places().document().id,
        name = name,
        latitude = lat,
        longitude = lng,
        radius = radius,
        enabled = true,
        ownerId = ownerId,
        visibility = if (isPublic) Visibility.PUBLIC else Visibility.PRIVATE,
        geohash = GeoFireUtils.getGeoHashForLocation(GeoLocation(lat, lng)),
        createdAt = System.currentTimeMillis()
    )

    fun savePlace(place: GeofenceArea): Task<Void> = places().document(place.id).set(place.toMap())

    fun getPlace(placeId: String): Task<GeofenceArea?> =
        places().document(placeId).get().continueWith { GeofenceArea.fromDoc(it.result) }

    /** Owners see every file; everyone else only public files (enforced by firestore.rules too). */
    fun loadFiles(placeId: String, isOwner: Boolean): Task<List<PlaceFile>> {
        val query = if (isOwner) files(placeId)
        else files(placeId).whereEqualTo("visibility", Visibility.PUBLIC)
        return query.get().continueWith { t ->
            t.result.documents
                .mapNotNull { PlaceFile.fromDoc(placeId, it) }
                .sortedByDescending { it.createdAt }
        }
    }

    /**
     * Uploads the bytes to Storage first, then writes the metadata. If the metadata write
     * fails the uploaded object is removed so nothing is orphaned.
     */
    fun uploadFile(
        placeId: String, ownerId: String, uri: Uri,
        displayName: String, mimeType: String, sizeBytes: Long, isPublic: Boolean,
        onProgress: (Int) -> Unit
    ): Task<PlaceFile> {
        val doc = files(placeId).document()
        val file = PlaceFile(
            id = doc.id,
            placeId = placeId,
            displayName = displayName,
            storagePath = FirestorePaths.storagePath(placeId, doc.id),
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            visibility = if (isPublic) Visibility.PUBLIC else Visibility.PRIVATE,
            ownerId = ownerId,
            createdAt = System.currentTimeMillis()
        )
        val ref = storage.reference.child(file.storagePath)
        val metadata = StorageMetadata.Builder()
            .setContentType(mimeType)
            .setCustomMetadata("displayName", displayName)
            .build()

        return ref.putFile(uri, metadata)
            .addOnProgressListener { snap ->
                if (snap.totalByteCount > 0) {
                    onProgress((100 * snap.bytesTransferred / snap.totalByteCount).toInt())
                }
            }
            .continueWithTask { upload ->
                if (!upload.isSuccessful) throw upload.exception!!
                doc.set(file.toMap())
            }
            .continueWithTask { write ->
                if (write.isSuccessful) return@continueWithTask Tasks.forResult(file)
                val error = write.exception!!
                ref.delete().continueWithTask { Tasks.forException<PlaceFile>(error) }
            }
    }

    fun deleteFile(file: PlaceFile): Task<Void> =
        storage.reference.child(file.storagePath).delete()
            .continueWithTask { files(file.placeId).document(file.id).delete() }

    /** Deletes every file (bytes + metadata) and then the place itself. */
    fun deletePlace(place: GeofenceArea): Task<Void> =
        files(place.id).get().continueWithTask { t ->
            val docs = t.result.documents
            val removals = docs.map { d ->
                val path = d.getString("storagePath")
                val removeBytes: Task<Void> =
                    if (path != null) storage.reference.child(path).delete()
                    else Tasks.forResult(null)
                // A missing object is fine; still drop the metadata.
                removeBytes.continueWithTask { d.reference.delete() }
            }
            Tasks.whenAll(removals)
        }.continueWithTask { t ->
            if (!t.isSuccessful) throw t.exception!!
            places().document(place.id).delete()
        }

    data class AccessUrl(val url: String, val expiresAt: Long)

    /**
     * Asks the server for a short-lived download URL. The server, not this app, checks that
     * the caller is the owner or is standing inside the place.
     */
    fun requestAccessUrl(
        file: PlaceFile, lat: Double?, lng: Double?, accuracyM: Float?
    ): Task<AccessUrl> {
        val payload = hashMapOf<String, Any>("placeId" to file.placeId, "fileId" to file.id)
        if (lat != null && lng != null) {
            payload["latitude"] = lat
            payload["longitude"] = lng
            payload["accuracy"] = (accuracyM ?: 0f).toDouble()
        }
        return functions.getHttpsCallable("getFileAccessUrl").call(payload).continueWith { t ->
            val data = t.result.data as? Map<*, *>
                ?: throw IllegalStateException("Empty response from server")
            AccessUrl(
                url = data["url"] as? String ?: throw IllegalStateException("No URL returned"),
                expiresAt = (data["expiresAt"] as? Number)?.toLong() ?: 0L
            )
        }
    }
}
