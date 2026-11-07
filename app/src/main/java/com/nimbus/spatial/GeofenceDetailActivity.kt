package com.nimbus.spatial

import android.app.Activity
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.util.Log
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth
import android.widget.Toast
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.Context
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.util.UUID
import java.io.File
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat



private const val FILE_PICK_REQUEST_CODE = 1001

class GeofenceDetailActivity : AppCompatActivity() {

    private lateinit var geofenceId: String
    private lateinit var geofenceName: String
    private lateinit var tvEmpty: TextView
    private lateinit var btnAddEntity: FloatingActionButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var entityAdapter: EntityAdapter
    private val entityItems = mutableListOf<EntityItem>()
    private val firestore = FirebaseFirestore.getInstance()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            uri?.let { handleFileSelection(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_geofence_detail)

        requestStoragePermissions()

        // Get passed data
        geofenceId = intent.getStringExtra("geofence_id") ?: ""
        geofenceName = intent.getStringExtra("geofence_name") ?: ""

        // Init views
        tvEmpty = findViewById(R.id.tv_empty)
        btnAddEntity = findViewById(R.id.btn_add_entity)
        recyclerView = findViewById(R.id.rv_entities)

        // Setup UI
        title = geofenceName
        recyclerView.layoutManager = LinearLayoutManager(this)
        entityAdapter = EntityAdapter(entityItems)
        recyclerView.adapter = entityAdapter

        btnAddEntity.setOnClickListener {
            openFilePicker()
        }

        loadEntitiesFromFirestore()

    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "application/pdf"))
        }
        filePickerLauncher.launch(intent)
    }

    private fun handleFileSelection(uri: Uri) {
        val file = File(uri.path ?: "")
        val fileName = file.name
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        saveEntityMetadataToFirestore(fileName, uri.toString(), mimeType)
    }

    private fun loadEntitiesFromFirestore() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        val userId = currentUser?.uid ?: run {
            Log.e("GeofenceDetailActivity", "User not authenticated")
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d("GeofenceDetailActivity", "Loading entities for geofenceId: $geofenceId, userId: $userId")
        firestore.collection("geofences")
            .document(geofenceId)
            .collection("entities")
            .whereEqualTo("ownerId", userId)
            .get()
            .addOnSuccessListener { result ->
                entityItems.clear()

                val isInsideGeofence = GeofenceStateStore.isInside(this, geofenceId)

                for (doc in result) {
                    val fileName = doc.getString("fileName") ?: "Unknown"
                    val localUri = doc.getString("localUri") ?: ""
                    val mimeType = doc.getString("mimeType") ?: "application/octet-stream"

                    entityItems.add(
                        EntityItem(
                            fileName = fileName,
                            localUri = localUri,
                            mimeType = mimeType,
                            isAccessible = isInsideGeofence
                        )
                    )
                }

                entityAdapter.notifyDataSetChanged()
                tvEmpty.visibility = if (entityItems.isEmpty()) View.VISIBLE else View.GONE
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load entities: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun requestStoragePermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (permissions.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
        }
    }

    private fun handlePickedFile(uri: Uri) {
        // Handle your picked file logic here
        Log.d("GeofenceDetailActivity", "File URI: $uri")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICK_REQUEST_CODE && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                Log.d("FilePicker", "Selected URI: $uri")

                // Try to open stream to verify
                try {
                    contentResolver.openInputStream(uri)?.use {
                        Log.d("FilePicker", "File access successful")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "File access failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }

                // Continue with saving metadata and showing in RecyclerView
                handlePickedFile(uri)
            }
        }
    }

    private fun saveEntityMetadataToFirestore(
        fileName: String,
        localUri: String,
        mimeType: String
    ) {
        val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        val entity = hashMapOf(
            "fileName" to fileName,
            "localUri" to localUri,
            "mimeType" to mimeType,
            "timestamp" to System.currentTimeMillis(),
            "ownerId" to currentUser.uid // ✅ REQUIRED FOR FIRESTORE RULE
        )

        firestore.collection("geofences")
            .document(geofenceId)
            .collection("entities")
            .add(entity)
            .addOnSuccessListener {
                Toast.makeText(this, "$fileName placed, On Point", Toast.LENGTH_SHORT).show()
                loadEntitiesFromFirestore()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Metadata save failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun getRealPathFromURI(context: Context, uri: Uri): String? {
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        val cursor = context.contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            cursor.moveToFirst()
            return cursor.getString(columnIndex)
        }
        return null
    }
}