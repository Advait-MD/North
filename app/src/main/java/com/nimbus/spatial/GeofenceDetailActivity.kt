package com.nimbus.spatial

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth

/** Lists the files attached to one geofence. Files open only while the user is inside it. */
class GeofenceDetailActivity : AppCompatActivity() {

    private lateinit var geofenceId: String
    private lateinit var userId: String
    private lateinit var tvStatus: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var entityAdapter: EntityAdapter
    private val entityItems = mutableListOf<EntityItem>()
    private var stateListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // ACTION_OPEN_DOCUMENT (unlike GET_CONTENT) gives a URI whose access can be kept across restarts.
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::onFilePicked)
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
        tvStatus = findViewById(R.id.tv_lock_status)
        tvEmpty = findViewById(R.id.tv_empty)

        entityAdapter = EntityAdapter(entityItems, ::openEntity)
        findViewById<RecyclerView>(R.id.rv_entities).apply {
            layoutManager = LinearLayoutManager(this@GeofenceDetailActivity)
            adapter = entityAdapter
        }
        findViewById<FloatingActionButton>(R.id.btn_add_entity).setOnClickListener {
            pickFile.launch(arrayOf("image/*", "video/*", "application/pdf"))
        }

        loadEntities()
    }

    override fun onStart() {
        super.onStart()
        if (!::geofenceId.isInitialized) return
        // Lock/unlock live if the user crosses the fence while this screen is open.
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
        entityAdapter.unlocked = unlocked
        tvStatus.text = if (unlocked) "Unlocked: you're inside this geofence" else "Locked: you're outside this geofence"
    }

    private fun loadEntities() {
        GeofenceRepository.loadEntities(geofenceId, userId)
            .addOnSuccessListener { items ->
                entityItems.clear()
                entityItems.addAll(items)
                entityAdapter.notifyDataSetChanged()
                tvEmpty.visibility = if (entityItems.isEmpty()) View.VISIBLE else View.GONE
            }
            .addOnFailureListener { toast("Failed to load files: ${it.message}") }
    }

    private fun onFilePicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "Provider doesn't allow persistent access to $uri", e)
        }
        val item = EntityItem(
            fileName = displayName(uri),
            uri = uri.toString(),
            mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        )
        GeofenceRepository.addEntity(geofenceId, userId, item)
            .addOnSuccessListener {
                toast("${item.fileName} placed, On Point")
                loadEntities()
            }
            .addOnFailureListener { toast("Couldn't save file: ${it.message}") }
    }

    /** The user-visible file name; a content URI's path is an opaque ID like "image:1000034567". */
    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "Unnamed file"

    private fun openEntity(item: EntityItem) {
        // Re-check at click time rather than trusting what the list showed.
        if (!isInside()) {
            toast("You're outside the geofence")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(item.uri), item.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("No app to open this file type")
        } catch (e: SecurityException) {
            // Files attached before persistent access was requested, or deleted/moved since.
            toast("This file is no longer accessible. Remove it and attach it again.")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_GEOFENCE_ID = "geofence_id"
        const val EXTRA_GEOFENCE_NAME = "geofence_name"
        private const val TAG = "GeofenceDetailActivity"
    }
}
