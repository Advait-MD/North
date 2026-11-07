package com.nimbus.spatial

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctionsException

class GeofenceDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLACE_ID = "geofence_id"
        const val EXTRA_PLACE_NAME = "geofence_name"
        private const val TAG = "GeofenceDetail"
        private val PICKABLE_TYPES = arrayOf("image/*", "video/*", "application/pdf")
    }

    private lateinit var placeId: String
    private var place: GeofenceArea? = null
    private var isOwner = false
    private var isInside = false

    private lateinit var tvEmpty: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnAddEntity: FloatingActionButton
    private lateinit var entityAdapter: EntityAdapter
    private val entityItems = mutableListOf<EntityItem>()

    private val uid get() = FirebaseAuth.getInstance().currentUser?.uid
    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }

    // The Storage Access Framework hands back a readable URI; no storage permission is needed.
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onFilePicked(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_geofence_detail)

        placeId = intent.getStringExtra(EXTRA_PLACE_ID) ?: run { finish(); return }
        title = intent.getStringExtra(EXTRA_PLACE_NAME) ?: "Place"

        tvEmpty = findViewById(R.id.tv_empty)
        tvStatus = findViewById(R.id.tv_status)
        progress = findViewById(R.id.progress_upload)
        btnAddEntity = findViewById(R.id.btn_add_entity)
        btnAddEntity.visibility = View.GONE // shown once we know the viewer owns the place
        btnAddEntity.setOnClickListener { pickFile.launch(PICKABLE_TYPES) }

        entityAdapter = EntityAdapter(entityItems, onOpen = { openFile(it) }, onLongPress = { onLongPress(it) })
        findViewById<RecyclerView>(R.id.rv_entities).apply {
            layoutManager = LinearLayoutManager(this@GeofenceDetailActivity)
            adapter = entityAdapter
        }

        loadPlace()
    }

    private fun loadPlace() {
        PlaceRepository.getPlace(placeId)
            .addOnSuccessListener { p ->
                if (p == null) {
                    Toast.makeText(this, "This place no longer exists", Toast.LENGTH_SHORT).show()
                    finish()
                    return@addOnSuccessListener
                }
                place = p
                isOwner = p.ownerId == uid
                title = p.name
                btnAddEntity.visibility = if (isOwner) View.VISIBLE else View.GONE
                isInside = GeofenceStateStore.isInside(this, p.id)
                renderStatus()
                loadFiles()
                refreshInsideFromLocation(p)
            }
            .addOnFailureListener {
                // firestore.rules deny reads of other people's private places.
                Toast.makeText(this, "You can't view this place", Toast.LENGTH_SHORT).show()
                finish()
            }
    }

    /** The geofence broadcast can lag; double-check against the last known location. */
    @SuppressLint("MissingPermission")
    private fun refreshInsideFromLocation(p: GeofenceArea) {
        if (!hasFineLocation()) return
        fusedLocation.lastLocation.addOnSuccessListener { loc ->
            if (loc == null) return@addOnSuccessListener
            val inside = GeoMath.isInside(
                loc.latitude, loc.longitude, loc.accuracy.toDouble(),
                p.latitude, p.longitude, p.radius.toDouble()
            )
            if (inside != isInside) {
                isInside = inside
                renderStatus()
                refreshAccess()
            }
        }
    }

    private fun renderStatus() {
        val p = place ?: return
        tvStatus.text = when {
            isOwner && isInside -> "Your place · ${visibilityLabel(p)} · you're here"
            isOwner -> "Your place · ${visibilityLabel(p)} · you can open files from anywhere"
            isInside -> "You're here: public files are unlocked"
            else -> "Go to this place to unlock its files"
        }
    }

    private fun visibilityLabel(p: GeofenceArea) = if (p.isPublic) "public" else "private"

    private fun loadFiles() {
        PlaceRepository.loadFiles(placeId, isOwner)
            .addOnSuccessListener { files ->
                entityItems.clear()
                entityItems += files.map { EntityItem(it, isAccessible = isOwner || isInside, isMine = isOwner) }
                entityAdapter.notifyDataSetChanged()
                tvEmpty.visibility = if (entityItems.isEmpty()) View.VISIBLE else View.GONE
            }
            .addOnFailureListener {
                Log.e(TAG, "Loading files failed", it)
                Toast.makeText(this, "Failed to load files: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun refreshAccess() {
        for (i in entityItems.indices) {
            entityItems[i] = entityItems[i].copy(isAccessible = isOwner || isInside)
        }
        entityAdapter.notifyDataSetChanged()
    }

    // region upload

    private fun onFilePicked(uri: Uri) {
        val p = place ?: return
        val owner = uid ?: return
        var displayName = uri.lastPathSegment ?: "file"
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }
                        ?.let { displayName = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }
                        ?.let { size = c.getLong(it) }
                }
            }
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        when {
            PICKABLE_TYPES.none { mimeMatches(it, mimeType) } ->
                return toast("Only photos, videos and PDFs are supported")
            size > PlaceRepository.MAX_UPLOAD_BYTES ->
                return toast("Files must be under ${PlaceRepository.MAX_UPLOAD_BYTES / (1024 * 1024)} MB")
        }

        if (!p.isPublic) {
            // Nobody else can see a private place, so its files are private too.
            upload(p, owner, uri, displayName, mimeType, size, isPublic = false)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Who can open \"$displayName\"?")
            .setItems(arrayOf("Anyone standing at ${p.name}", "Only me")) { _, which ->
                upload(p, owner, uri, displayName, mimeType, size, isPublic = which == 0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun upload(
        p: GeofenceArea, owner: String, uri: Uri,
        displayName: String, mimeType: String, size: Long, isPublic: Boolean
    ) {
        progress.progress = 0
        progress.visibility = View.VISIBLE
        btnAddEntity.isEnabled = false
        PlaceRepository.uploadFile(
            p.id, owner, uri, displayName, mimeType, size.coerceAtLeast(0), isPublic,
            onProgress = { progress.progress = it }
        ).addOnCompleteListener { task ->
            progress.visibility = View.GONE
            btnAddEntity.isEnabled = true
            if (task.isSuccessful) {
                toast("$displayName placed, On Point")
                loadFiles()
            } else {
                Log.e(TAG, "Upload failed", task.exception)
                toast("Upload failed: ${task.exception?.message}")
            }
        }
    }

    private fun mimeMatches(pattern: String, type: String) =
        if (pattern.endsWith("/*")) type.startsWith(pattern.dropLast(1)) else pattern == type

    // endregion

    // region open / delete

    @SuppressLint("MissingPermission")
    private fun openFile(item: EntityItem) {
        if (!item.isAccessible) return toast("Go to ${place?.name ?: "this place"} to open this file")
        toast("Checking access…")
        if (!hasFineLocation()) {
            requestUrl(item.file, null, null, null)
            return
        }
        // A fresh fix, not a cached one: the server verifies it against the place's radius.
        fusedLocation.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                requestUrl(item.file, loc?.latitude, loc?.longitude, loc?.accuracy)
            }
            .addOnFailureListener { requestUrl(item.file, null, null, null) }
    }

    private fun requestUrl(file: PlaceFile, lat: Double?, lng: Double?, accuracy: Float?) {
        PlaceRepository.requestAccessUrl(file, lat, lng, accuracy)
            .addOnSuccessListener { access ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(access.url), file.mimeType)
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "No viewer for ${file.mimeType}", e)
                    toast("No app to open this file type")
                }
            }
            .addOnFailureListener { e ->
                val message = (e as? FirebaseFunctionsException)?.let {
                    when (it.code) {
                        FirebaseFunctionsException.Code.PERMISSION_DENIED -> it.message ?: "You're not at this place"
                        FirebaseFunctionsException.Code.NOT_FOUND -> "This file no longer exists"
                        FirebaseFunctionsException.Code.UNAUTHENTICATED -> "Please sign in again"
                        else -> it.message
                    }
                } ?: e.message
                toast("Can't open file: $message")
            }
    }

    private fun onLongPress(item: EntityItem) {
        if (!item.isMine) return
        AlertDialog.Builder(this)
            .setTitle("Delete \"${item.file.displayName}\"?")
            .setPositiveButton("Delete") { _, _ ->
                PlaceRepository.deleteFile(item.file)
                    .addOnSuccessListener { toast("Deleted"); loadFiles() }
                    .addOnFailureListener { toast("Delete failed: ${it.message}") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // endregion

    private fun hasFineLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
