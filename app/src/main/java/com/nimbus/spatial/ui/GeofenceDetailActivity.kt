package com.nimbus.spatial.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.nimbus.spatial.R
import com.nimbus.spatial.data.AttachedFile
import com.nimbus.spatial.data.GeofenceArea
import com.nimbus.spatial.data.GeofenceRepository
import com.nimbus.spatial.geofence.GeofenceStateStore
import com.nimbus.spatial.security.FileVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The files attached to one geofence. Tap + to attach (the file is encrypted into FileVault),
 * tap a file to open it (GatedFileOpener checks the security gates), long-press to remove it.
 */
class GeofenceDetailActivity : AppCompatActivity() {

    private lateinit var fence: GeofenceArea
    private lateinit var vault: FileVault
    private lateinit var lockStatus: TextView
    private lateinit var emptyMessage: TextView
    private lateinit var attachButton: FloatingActionButton
    private lateinit var adapter: AttachedFileAdapter
    private lateinit var opener: GatedFileOpener
    private val files = mutableListOf<AttachedFile>()
    private var stateListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val user = FirebaseAuth.getInstance().currentUser
        val area = fenceFrom(intent)
        if (user == null || area == null) {
            finish()
            return
        }
        fence = area.copy(ownerId = user.uid)
        vault = FileVault(this, user.uid)
        opener = GatedFileOpener(this, vault, fence)

        // Keep file names out of screenshots, screen recordings and the recent-apps preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_geofence_detail)
        setUpViews()
        loadFiles()
    }

    private fun setUpViews() {
        findViewById<TextView>(R.id.tv_geofence_name).text = fence.name.ifEmpty { "Geofence" }
        lockStatus = findViewById(R.id.tv_lock_status)
        emptyMessage = findViewById(R.id.tv_empty)

        adapter = AttachedFileAdapter(files, onOpen = opener::open, onRemove = ::confirmRemove)
        findViewById<RecyclerView>(R.id.rv_files).apply {
            layoutManager = LinearLayoutManager(this@GeofenceDetailActivity)
            adapter = this@GeofenceDetailActivity.adapter
        }
        attachButton = findViewById(R.id.btn_attach_file)
        attachButton.setOnClickListener { pickFile.launch(arrayOf("image/*", "video/*", "application/pdf")) }
    }

    override fun onStart() {
        super.onStart()
        // Back from a viewer app: wipe the decrypted copy it was showing.
        FileVault.clearDecrypted(this, fence.id)
        // Lock or unlock live if the user crosses the fence while this screen is open.
        refreshLockState()
        stateListener = GeofenceStateStore.addListener(this, ::refreshLockState)
    }

    override fun onStop() {
        stateListener?.let { GeofenceStateStore.removeListener(this, it) }
        stateListener = null
        super.onStop()
    }

    override fun onDestroy() {
        if (::opener.isInitialized) opener.cancel()
        super.onDestroy()
    }

    private fun isInside() = GeofenceStateStore.isInside(this, fence.id)

    private fun refreshLockState() {
        val unlocked = isInside()
        adapter.unlocked = unlocked
        lockStatus.text =
            if (unlocked) "Unlocked: you're inside this geofence" else "Locked: you're outside this geofence"
    }

    private fun loadFiles() {
        GeofenceRepository.loadFiles(fence.id, fence.ownerId)
            .addOnSuccessListener { loaded ->
                files.clear()
                files.addAll(loaded)
                adapter.notifyDataSetChanged()
                emptyMessage.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            }
            .addOnFailureListener { toast("Failed to load files: ${it.message}") }
    }

    // ---- Attaching: encrypt, save metadata, then offer to delete the original ----

    private fun attachFile(original: Uri) {
        val file = AttachedFile(
            id = UUID.randomUUID().toString(),
            name = displayName(original),
            mimeType = contentResolver.getType(original) ?: "application/octet-stream"
        )
        attachButton.isEnabled = false
        toast("Encrypting ${file.name}...")

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    vault.encrypt(original, fence.id, file.id)
                    vault.pinFence(fence)
                }
            } catch (e: Exception) {
                toast("Couldn't encrypt ${file.name}: ${e.message}", long = true)
                attachButton.isEnabled = true
                return@launch
            }
            GeofenceRepository.addEncryptedFile(fence.id, fence.ownerId, file)
                .addOnSuccessListener {
                    loadFiles()
                    OriginalFileCleanup.offer(this@GeofenceDetailActivity, original, file.name)
                }
                .addOnFailureListener {
                    lifecycleScope.launch(Dispatchers.IO) { vault.delete(fence.id, file.id) }
                    toast("Couldn't attach file: ${it.message}")
                }
                .addOnCompleteListener { attachButton.isEnabled = true }
        }
    }

    /** The name the user sees in their file manager (the URI itself only contains an opaque ID). */
    private fun displayName(uri: Uri): String {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        return name ?: uri.lastPathSegment ?: "Unnamed file"
    }

    // ---- Removing ----

    private fun confirmRemove(file: AttachedFile) {
        val message =
            if (file.isEncrypted) "This deletes North's encrypted copy. If you deleted the original, the file is gone for good."
            else "It's only detached from this place. The file stays on your phone."
        AlertDialog.Builder(this)
            .setTitle("Remove ${file.name}?")
            .setMessage(message)
            .setPositiveButton("Remove") { _, _ -> removeFile(file) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeFile(file: AttachedFile) {
        GeofenceRepository.deleteFile(fence.id, file.id)
            .addOnSuccessListener {
                lifecycleScope.launch(Dispatchers.IO) { vault.delete(fence.id, file.id) }
                loadFiles()
            }
            .addOnFailureListener { toast("Couldn't remove file: ${it.message}") }
    }

    companion object {
        private const val EXTRA_ID = "geofence_id"
        private const val EXTRA_NAME = "geofence_name"
        private const val EXTRA_LATITUDE = "geofence_latitude"
        private const val EXTRA_LONGITUDE = "geofence_longitude"
        private const val EXTRA_RADIUS = "geofence_radius"

        /** This activity isn't exported, so only North itself can start it with these extras. */
        fun intent(context: Context, area: GeofenceArea): Intent =
            Intent(context, GeofenceDetailActivity::class.java)
                .putExtra(EXTRA_ID, area.id)
                .putExtra(EXTRA_NAME, area.name)
                .putExtra(EXTRA_LATITUDE, area.latitude)
                .putExtra(EXTRA_LONGITUDE, area.longitude)
                .putExtra(EXTRA_RADIUS, area.radius)

        private fun fenceFrom(intent: Intent): GeofenceArea? {
            val id = intent.getStringExtra(EXTRA_ID)
            if (id.isNullOrEmpty() || !intent.hasExtra(EXTRA_RADIUS)) return null
            return GeofenceArea(
                id = id,
                name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
                latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0),
                longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0),
                radius = intent.getFloatExtra(EXTRA_RADIUS, 0f)
            )
        }
    }
}
