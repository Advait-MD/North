package com.nimbus.spatial.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
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
 * One place and its files. "Attach file" encrypts a copy into FileVault, tapping a file opens it
 * (GatedFileOpener checks the security gates), long-pressing removes it.
 */
class GeofenceDetailActivity : AppCompatActivity() {

    private lateinit var fence: GeofenceArea
    private lateinit var vault: FileVault
    private lateinit var opener: GatedFileOpener
    private lateinit var adapter: AttachedFileAdapter
    private lateinit var progress: View
    private lateinit var emptyState: View
    private lateinit var attachButton: ExtendedFloatingActionButton
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
        opener = GatedFileOpener(this, vault, fence, onBusy = ::setBusy)

        enableEdgeToEdge()
        // Keep file names out of screenshots, screen recordings and the recent-apps preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_geofence_detail)
        setUpViews()
        loadFiles()
    }

    private fun setUpViews() {
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = fence.name.ifEmpty { getString(R.string.app_name) }
            setNavigationOnClickListener { finish() }
        }
        progress = findViewById(R.id.progress)
        emptyState = findViewById(R.id.empty_state)

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
        // Lock or unlock live if the user crosses the place's edge while this screen is open.
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

    /** Teal "Unlocked" card when inside, grey "Locked" card when outside. */
    private fun refreshLockState() {
        val unlocked = GeofenceStateStore.isInside(this, fence.id)
        adapter.unlocked = unlocked

        val background = if (unlocked) R.color.north_tertiary_container else R.color.north_surface_variant
        val foreground = if (unlocked) R.color.north_on_tertiary_container else R.color.north_on_surface_variant
        val foregroundColor = ContextCompat.getColor(this, foreground)

        findViewById<MaterialCardView>(R.id.card_lock_status)
            .setCardBackgroundColor(ContextCompat.getColor(this, background))
        findViewById<ImageView>(R.id.iv_lock_status).apply {
            setImageResource(if (unlocked) R.drawable.ic_lock_open else R.drawable.ic_lock)
            setColorFilter(foregroundColor)
        }
        findViewById<TextView>(R.id.tv_lock_title).apply {
            setText(if (unlocked) R.string.detail_unlocked_title else R.string.detail_locked_title)
            setTextColor(foregroundColor)
        }
        findViewById<TextView>(R.id.tv_lock_body).apply {
            setText(if (unlocked) R.string.detail_unlocked_body else R.string.detail_locked_body)
            setTextColor(foregroundColor)
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        attachButton.isEnabled = !busy
    }

    private fun loadFiles() {
        GeofenceRepository.loadFiles(fence.id, fence.ownerId)
            .addOnSuccessListener { loaded ->
                files.clear()
                files.addAll(loaded.sortedBy { it.name.lowercase() })
                adapter.notifyDataSetChanged()
                emptyState.isVisible = files.isEmpty()
            }
            .addOnFailureListener { showMessage("Couldn't load files: ${it.message}", long = true) }
    }

    // ---- Attaching: encrypt, save metadata, then offer to delete the original ----

    private fun attachFile(original: Uri) {
        val file = AttachedFile(
            id = UUID.randomUUID().toString(),
            name = displayName(original),
            mimeType = contentResolver.getType(original) ?: "application/octet-stream"
        )
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    vault.encrypt(original, fence.id, file.id)
                    vault.pinFence(fence)
                }
            } catch (e: Exception) {
                setBusy(false)
                showMessage("Couldn't encrypt ${file.name}: ${e.message}", long = true)
                return@launch
            }
            GeofenceRepository.addEncryptedFile(fence.id, fence.ownerId, file)
                .addOnSuccessListener {
                    loadFiles()
                    OriginalFileCleanup.offer(this@GeofenceDetailActivity, original, file.name)
                }
                .addOnFailureListener {
                    lifecycleScope.launch(Dispatchers.IO) { vault.delete(fence.id, file.id) }
                    showMessage("Couldn't attach ${file.name}: ${it.message}", long = true)
                }
                .addOnCompleteListener { setBusy(false) }
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
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove ${file.name}?")
            .setMessage(message)
            .setPositiveButton("Remove") { _, _ -> removeFile(file) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun removeFile(file: AttachedFile) {
        GeofenceRepository.deleteFile(fence.id, file.id)
            .addOnSuccessListener {
                lifecycleScope.launch(Dispatchers.IO) { vault.delete(fence.id, file.id) }
                loadFiles()
                showMessage("${file.name} removed")
            }
            .addOnFailureListener { showMessage("Couldn't remove ${file.name}: ${it.message}", long = true) }
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
