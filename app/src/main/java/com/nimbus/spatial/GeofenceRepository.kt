package com.nimbus.spatial

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QueryDocumentSnapshot

/**
 * All Firestore access in one place. Every query is scoped by ownerId, because the security
 * rules (firestore.rules) reject any query that could return another user's documents.
 *
 * Schema:
 *   geofence_areas/{areaId}                 {id, name, latitude, longitude, radius, isEnabled, ownerId}
 *   geofences/{areaId}/entities/{entityId}  {fileName, localUri, mimeType, timestamp, ownerId}
 */
object GeofenceRepository {
    private const val AREAS = "geofence_areas"
    private const val MAX_BATCH_WRITES = 450 // Firestore's limit is 500 per batch

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun entities(areaId: String): CollectionReference =
        db.collection("geofences").document(areaId).collection("entities")

    fun loadAreas(ownerId: String): Task<List<GeofenceArea>> =
        db.collection(AREAS)
            .whereEqualTo("ownerId", ownerId)
            .get()
            .continueWith { task ->
                if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Query failed")
                task.result.map(::toArea)
            }

    private fun toArea(doc: QueryDocumentSnapshot): GeofenceArea {
        val area = doc.toObject(GeofenceArea::class.java)
        // Documents written before @PropertyName was added store the flag as "enabled".
        val enabled = doc.getBoolean("isEnabled") ?: doc.getBoolean("enabled") ?: true
        return area.copy(id = area.id.ifEmpty { doc.id }, isEnabled = enabled)
    }

    fun saveArea(area: GeofenceArea): Task<Void> =
        db.collection(AREAS).document(area.id).set(area)

    fun areaName(areaId: String): Task<String?> =
        db.collection(AREAS).document(areaId).get().continueWith { task ->
            if (task.isSuccessful) task.result.getString("name") else null
        }

    /** Deletes the area's attached-file records first so nothing is left orphaned, then the area. */
    fun deleteArea(areaId: String, ownerId: String): Task<Void> =
        entities(areaId).whereEqualTo("ownerId", ownerId).get()
            .continueWithTask { task ->
                if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Query failed")
                val commits = task.result.documents.chunked(MAX_BATCH_WRITES).map { chunk ->
                    db.batch().apply { chunk.forEach { delete(it.reference) } }.commit()
                }
                Tasks.whenAll(commits)
            }
            .onSuccessTask { db.collection(AREAS).document(areaId).delete() }

    fun loadEntities(areaId: String, ownerId: String): Task<List<EntityItem>> =
        entities(areaId)
            .whereEqualTo("ownerId", ownerId)
            .get()
            .continueWith { task ->
                if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Query failed")
                task.result.map { doc ->
                    EntityItem(
                        fileName = doc.getString("fileName") ?: "Unknown",
                        uri = doc.getString("localUri") ?: "",
                        mimeType = doc.getString("mimeType") ?: "application/octet-stream"
                    )
                }
            }

    fun addEntity(areaId: String, ownerId: String, item: EntityItem): Task<DocumentReference> =
        entities(areaId).add(
            hashMapOf(
                "fileName" to item.fileName,
                "localUri" to item.uri,
                "mimeType" to item.mimeType,
                "timestamp" to System.currentTimeMillis(),
                "ownerId" to ownerId
            )
        )
}
