package com.nimbus.spatial

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.*
import com.google.android.material.slider.Slider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private lateinit var adapter: GeofenceAdapter
    private lateinit var firestore: FirebaseFirestore
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geofencingClient: GeofencingClient
    private lateinit var geofencePendingIntent: PendingIntent
    private var locationCallback: LocationCallback? = null
    private var locationFeaturesStarted = false

    private var currentLocation: Location? = null
    private val geofenceList = mutableListOf<GeofenceArea>()

    // UI elements
    private lateinit var btnCreate: Button
    private lateinit var slider: Slider
    private lateinit var tvRadius: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val rootView = window.decorView.findViewById<View>(android.R.id.content)
                val blur = RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
                rootView.setRenderEffect(blur)
            }
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            return // Don't finish(), just let it come back
        }

        setContentView(R.layout.activity_main)

        // Enable Firestore logs (optional)
        FirebaseFirestore.setLoggingEnabled(true)
        firestore = FirebaseFirestore.getInstance()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        geofencingClient = LocationServices.getGeofencingClient(this)
        geofencePendingIntent = PendingIntent.getBroadcast(
            this, 0, Intent(this, GeofenceBroadcastReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        findViewById<Button>(R.id.logoutButton).setOnClickListener {
            // The next account on this device must not inherit this user's fences or inside-state.
            geofencingClient.removeGeofences(geofencePendingIntent)
            GeofenceStateStore.clear(this)
            FirebaseAuth.getInstance().signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        setupUI()
        loadGeofences()

        // Location work only starts once permission is actually granted (see onRequestPermissionsResult).
        if (hasFineLocation()) onForegroundLocationGranted() else requestForegroundPermissions()
    }

    override fun onStart() {
        super.onStart()
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val rootView = window.decorView.findViewById<View>(android.R.id.content)
            rootView.setRenderEffect(null)
        }
    }

    // ---- Permissions ----

    private fun hasPermission(permission: String) =
        ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasFineLocation() = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBackgroundLocation() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /**
     * Foreground location only. On Android 11+ the system ignores a request that also contains
     * ACCESS_BACKGROUND_LOCATION and grants nothing, so background is asked for separately.
     */
    private fun requestForegroundPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_FOREGROUND_PERMISSIONS)
    }

    private fun maybeRequestBackgroundLocation() {
        if (hasBackgroundLocation()) return
        AlertDialog.Builder(this)
            .setTitle("Allow location all the time")
            .setMessage(
                "North unlocks your files when you enter a geofence, even while the app is closed. " +
                    "On the next screen, choose \"Allow all the time\"."
            )
            .setPositiveButton("Continue") { _, _ ->
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    REQ_BACKGROUND_LOCATION
                )
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_FOREGROUND_PERMISSIONS ->
                if (hasFineLocation()) {
                    onForegroundLocationGranted()
                } else {
                    Toast.makeText(this, "Location permission is needed to create geofences", Toast.LENGTH_LONG).show()
                }

            REQ_BACKGROUND_LOCATION ->
                if (hasBackgroundLocation()) {
                    registerEnabledGeofences()
                } else {
                    Toast.makeText(this, "Geofences won't trigger in the background without \"Allow all the time\"", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun onForegroundLocationGranted() {
        if (locationFeaturesStarted) return
        locationFeaturesStarted = true
        startLocationUpdates()
        // Android 14 throws if a location foreground service starts before permission is granted.
        startForegroundService()
        registerEnabledGeofences()
        maybeRequestBackgroundLocation()
    }

    // ---- UI ----

    private fun setupUI() {
        slider = findViewById(R.id.seekbar_radius)
        tvRadius = findViewById(R.id.tv_selected_radius)
        btnCreate = findViewById(R.id.btn_create_geofence)

        slider.addOnChangeListener { _, value, _ ->
            tvRadius.text = "Selected Radius: ${value.toInt()} meters"
        }

        btnCreate.setOnClickListener {
            currentLocation?.let {
                showNameDialog(it.latitude, it.longitude, slider.value)
            } ?: Toast.makeText(this, "Waiting for location...", Toast.LENGTH_SHORT).show()
        }

        adapter = GeofenceAdapter(
            geofenceList,
            onDelete = { deleteGeofence(it) },
            onClick = { geofence ->
                val intent = Intent(this, GeofenceDetailActivity::class.java)
                intent.putExtra("geofence_id", geofence.id)
                startActivity(intent)
            }
        )

        findViewById<RecyclerView>(R.id.recycler_geofences).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
    }

    private fun startForegroundService() {
        val intent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else
            startService(intent)
    }

    private fun startLocationUpdates() {
        if (!hasFineLocation()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                currentLocation = res.lastLocation
                Log.d(TAG, "Location update: $currentLocation")
            }
        }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(req, callback, mainLooper)
    }

    private fun showNameDialog(lat: Double, lng: Double, radius: Float) {
        val input = EditText(this).apply { hint = "Enter area name" }
        AlertDialog.Builder(this)
            .setTitle("Name this Geofence")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val userId = FirebaseAuth.getInstance().currentUser?.uid
                if (userId == null) {
                    Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val a = GeofenceArea(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    latitude = lat,
                    longitude = lng,
                    radius = radius,
                    isEnabled = true,
                    ownerId = userId
                )

                firestore.collection("geofence_areas")
                    .document(a.id)
                    .set(a)
                    .addOnSuccessListener {
                        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                        geofenceList.add(a)
                        adapter.notifyItemInserted(geofenceList.size - 1)
                        addGeofences(listOf(a))
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Geofences ----

    /** Loads only the signed-in user's geofences, shows them, and registers the enabled ones. */
    private fun loadGeofences() {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        firestore.collection("geofence_areas")
            .whereEqualTo("ownerId", userId) // required: the security rules reject unscoped queries
            .get()
            .addOnSuccessListener { result ->
                geofenceList.clear()
                for (doc in result) {
                    // Documents written before @PropertyName was added store the flag as "enabled".
                    val enabled = doc.getBoolean("isEnabled") ?: doc.getBoolean("enabled") ?: true
                    geofenceList.add(doc.toObject(GeofenceArea::class.java).copy(isEnabled = enabled))
                }
                adapter.notifyDataSetChanged()
                registerEnabledGeofences()
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to load geofences: ${it.message}")
                Toast.makeText(this, "Failed to load geofences", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Re-registers the user's enabled fences. Safe to call repeatedly: same request IDs replace
     * the existing fences, and the repeated initial ENTER is harmless because state is a set.
     */
    private fun registerEnabledGeofences() {
        addGeofences(geofenceList.filter { it.isEnabled && it.id.isNotEmpty() })
    }

    private fun addGeofences(areas: List<GeofenceArea>) {
        if (areas.isEmpty()) return
        if (!hasBackgroundLocation()) {
            // On Android 10+ the Geofencing API rejects fences without background location.
            Log.w(TAG, "Background location not granted; not registering ${areas.size} geofence(s)")
            return
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(areas.map { area ->
                Geofence.Builder()
                    .setRequestId(area.id)
                    .setCircularRegion(area.latitude, area.longitude, area.radius)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            })
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return

        geofencingClient.addGeofences(request, geofencePendingIntent)
            .addOnSuccessListener { Log.d(TAG, "Registered geofences ${areas.map { it.id }}") }
            .addOnFailureListener { Log.e(TAG, "Registering geofences failed: ${it.message}") }
    }

    private fun removeGeofence(id: String) {
        geofencingClient.removeGeofences(listOf(id))
            .addOnSuccessListener { Log.d(TAG, "Removed geofence: $id") }
            .addOnFailureListener { Log.e(TAG, "Remove geofence $id failed: ${it.message}") }
    }

    private fun deleteGeofence(area: GeofenceArea) {
        firestore.collection("geofence_areas").document(area.id)
            .delete()
            .addOnSuccessListener {
                removeGeofence(area.id)
                GeofenceStateStore.onExit(this, listOf(area.id))

                val idx = geofenceList.indexOfFirst { it.id == area.id }
                if (idx != -1) {
                    geofenceList.removeAt(idx)
                    adapter.notifyItemRemoved(idx)
                }
                Toast.makeText(this, "${area.name} deleted", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Log.e("North", "Failed to delete geofence ${area.id}: ${it.message}")
                Toast.makeText(this, "Delete failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    override fun onDestroy() {
        // lateinit/nullable checks: onCreate returns early when logged out, and the callback
        // only exists once location permission was granted.
        val callback = locationCallback
        if (::fusedLocationClient.isInitialized && callback != null) {
            fusedLocationClient.removeLocationUpdates(callback)
        }
        super.onDestroy()
    }

    private companion object {
        const val REQ_FOREGROUND_PERMISSIONS = 1001
        const val REQ_BACKGROUND_LOCATION = 1002
    }
}
