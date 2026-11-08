package com.nimbus.spatial.data

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore

/**
 * All Firestore reads and writes. Every query filters by ownerId: the security rules
 * (firestore.rules) reject any query that could return another user's documents.
 *
 * Firestore layout:
 *   geofence_areas/{areaId}                 -> GeofenceArea
 *   geofences/{areaId}/entities/{fileId}    -> {fileName, localUri, mimeType, timestamp, ownerId}
 */
object GeofenceRepository {
    private const val MAX_WRITES_PER_BATCH = 450 // Firestore allows 500
    private const val DEFAULT_MIME_TYPE = "application/octet-stream"

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun areas(): CollectionReference = db.collection("geofence_areas")

    private fun files(areaId: String): CollectionReference =
        db.collection("geofences").document(areaId).collection("entities")

    // ---- Geofence areas ----

    fun loadAreas(ownerId: String): Task<List<GeofenceArea>> =
        areas().whereEqualTo("ownerId", ownerId).get().mapResult { snapshot ->
            snapshot.map { doc -> doc.toObject(GeofenceArea::class.java).copy(id = doc.id) }
        }

    fun saveArea(area: GeofenceArea): Task<Void> = areas().document(area.id).set(area)

    fun areaName(areaId: String): Task<String?> =
        areas().document(areaId).get().mapResult { it.getString("name") }

    /** Deletes the area's file records first, so a half-finished delete never leaves orphans. */
    fun deleteArea(areaId: String, ownerId: String): Task<Void> =
        files(areaId).whereEqualTo("ownerId", ownerId).get()
            .onSuccessTask { snapshot -> deleteAll(snapshot.documents.map { it.reference }) }
            .onSuccessTask { areas().document(areaId).delete() }

    // ---- Attached files ----

    fun loadFiles(areaId: String, ownerId: String): Task<List<AttachedFile>> =
        files(areaId).whereEqualTo("ownerId", ownerId).get().mapResult { snapshot ->
            snapshot.map { doc ->
                AttachedFile(
                    id = doc.id,
                    name = doc.getString("fileName") ?: "Unknown",
                    uri = doc.getString("localUri").orEmpty(),
                    mimeType = doc.getString("mimeType") ?: DEFAULT_MIME_TYPE
                )
            }
        }

    fun addFile(areaId: String, ownerId: String, file: AttachedFile): Task<DocumentReference> =
        files(areaId).add(
            mapOf(
                "fileName" to file.name,
                "localUri" to file.uri,
                "mimeType" to file.mimeType,
                "timestamp" to System.currentTimeMillis(),
                "ownerId" to ownerId
            )
        )

    fun deleteFile(areaId: String, fileId: String): Task<Void> = files(areaId).document(fileId).delete()

    // ---- Helpers ----

    private fun deleteAll(refs: List<DocumentReference>): Task<Void> =
        Tasks.whenAll(
            refs.chunked(MAX_WRITES_PER_BATCH).map { chunk ->
                db.batch().apply { chunk.forEach { delete(it) } }.commit()
            }
        )

    /** Transforms a successful result. Unlike continueWith, a failure keeps its original exception. */
    private fun <T, R> Task<T>.mapResult(transform: (T) -> R): Task<R> =
        onSuccessTask { Tasks.forResult(transform(it)) }
}
