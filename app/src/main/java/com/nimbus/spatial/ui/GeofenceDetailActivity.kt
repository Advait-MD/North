package com.nimbus.spatial.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.nimbus.spatial.R
import com.nimbus.spatial.data.AttachedFile
import com.nimbus.spatial.data.GeofenceRepository
import com.nimbus.spatial.geofence.GeofenceStateStore

/**
 * The files attached to one geofence. They open only while the user is inside it.
 * Tap + to attach a file, tap a file to open it, long-press to remove it.
 */
class GeofenceDetailActivity : AppCompatActivity() {

    private lateinit var geofenceId: String
    private lateinit var userId: String
    private lateinit var lockStatus: TextView
    private lateinit var emptyMessage: TextView
    private lateinit var adapter: AttachedFileAdapter
    private val files = mutableListOf<AttachedFile>()
    private var stateListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // OPEN_DOCUMENT (unlike GET_CONTENT) gives a URI we can keep access to after a restart.
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val user = FirebaseAuth.getInstance().currentUser
        val id = intent.getStringExtra(EXTRA_GEOFENCE_ID)
        if (user == null || id.isNullOrEmpty()) {
            finish()
            return
        }
        userId = user.uid
        geofenceId = id

        setContentView(R.layout.activity_geofence_detail)
        findViewById<TextView>(R.id.tv_geofence_name).text =
            intent.getStringExtra(EXTRA_GEOFENCE_NAME).orEmpty().ifEmpty { "Geofence" }
        lockStatus = findViewById(R.id.tv_lock_status)
        emptyMessage = findViewById(R.id.tv_empty)

        adapter = AttachedFileAdapter(files, onOpen = ::openFile, onRemove = ::confirmRemove)
        findViewById<RecyclerView>(R.id.rv_files).apply {
            layoutManager = LinearLayoutManager(this@GeofenceDetailActivity)
            adapter = this@GeofenceDetailActivity.adapter
        }
        findViewById<FloatingActionButton>(R.id.btn_attach_file).setOnClickListener {
            pickFile.launch(arrayOf("image/*", "video/*", "application/pdf"))
        }

        loadFiles()
    }

    override fun onStart() {
        super.onStart()
        // Lock or unlock live if the user crosses the fence while this screen is open.
        refreshLockState()
        stateListener = GeofenceStateStore.addListener(this, ::refreshLockState)
    }

    override fun onStop() {
        stateListener?.let { GeofenceStateStore.removeListener(this, it) }
        stateListener = null
        super.onStop()
    }

    private fun isInside() = GeofenceStateStore.isInside(this, geofenceId)

    private fun refreshLockState() {
        val unlocked = isInside()
        adapter.unlocked = unlocked
        lockStatus.text =
            if (unlocked) "Unlocked: you're inside this geofence" else "Locked: you're outside this geofence"
    }

    private fun loadFiles() {
        GeofenceRepository.loadFiles(geofenceId, userId)
            .addOnSuccessListener { loaded ->
                files.clear()
                files.addAll(loaded)
                adapter.notifyDataSetChanged()
                emptyMessage.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            }
            .addOnFailureListener { toast("Failed to load files: ${it.message}") }
    }

    private fun attachFile(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w("GeofenceDetail", "This provider doesn't allow keeping access to $uri", e)
        }
        val file = AttachedFile(
            id = "",
            name = displayName(uri),
            uri = uri.toString(),
            mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        )
        GeofenceRepository.addFile(geofenceId, userId, file)
            .addOnSuccessListener {
                toast("${file.name} placed, On Point")
                loadFiles()
            }
            .addOnFailureListener { toast("Couldn't attach file: ${it.message}") }
    }

    /** The name the user sees in their file manager (the URI itself only contains an opaque ID). */
    private fun displayName(uri: Uri): String {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        return name ?: uri.lastPathSegment ?: "Unnamed file"
    }

    private fun openFile(file: AttachedFile) {
        // Check again on tap instead of trusting what the list showed.
        if (!isInside()) {
            toast("You're outside the geofence")
            return
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(file.uri), file.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            toast("No app to open this file type")
        } catch (e: SecurityException) {
            // Moved or deleted since, or attached by an older version that didn't keep access.
            toast("North can't open this file any more. Long-press it to remove it.", long = true)
        }
    }

    private fun confirmRemove(file: AttachedFile) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${file.name}?")
            .setMessage("It's only detached from this place. The file stays on your phone.")
            .setPositiveButton("Remove") { _, _ ->
                GeofenceRepository.deleteFile(geofenceId, file.id)
                    .addOnSuccessListener { loadFiles() }
                    .addOnFailureListener { toast("Couldn't remove file: ${it.message}") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val EXTRA_GEOFENCE_ID = "geofence_id"
        const val EXTRA_GEOFENCE_NAME = "geofence_name"
    }
}
