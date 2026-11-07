package com.nimbus.spatial

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.slider.Slider
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var userId: String
    private lateinit var adapter: GeofenceAdapter
    private lateinit var btnCreate: Button
    private lateinit var slider: Slider
    private lateinit var tvRadius: TextView

    private val geofenceList = mutableListOf<GeofenceArea>()
    private val locationCancellation = CancellationTokenSource()
    private var locationFeaturesStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            // finish() here means onStart() never runs, so LoginActivity is only launched once.
            openLogin()
            return
        }
        userId = user.uid

        setContentView(R.layout.activity_main)
        setupUI()
        loadGeofences()

        // Location work only starts once permission is actually granted (see onRequestPermissionsResult).
        if (hasFineLocation()) onForegroundLocationGranted() else requestForegroundPermissions()
    }

    override fun onStart() {
        super.onStart()
        // Covers being signed out while the activity was in the background.
        if (FirebaseAuth.getInstance().currentUser == null) openLogin()
    }

    override fun onDestroy() {
        locationCancellation.cancel()
        super.onDestroy()
    }

    private fun openLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun logout() {
        // The next account on this device must not inherit this user's fences or inside-state.
        GeofenceRegistrar.removeAll(this)
        GeofenceStateStore.clear(this)
        stopService(Intent(this, LocationService::class.java))
        FirebaseAuth.getInstance().signOut()
        openLogin()
    }

    // ---- Permissions ----

    private fun hasFineLocation() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

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
        if (GeofenceRegistrar.hasRequiredPermissions(this)) return
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
                if (hasFineLocation()) onForegroundLocationGranted()
                else toast("Location permission is needed to create geofences", long = true)

            REQ_BACKGROUND_LOCATION ->
                if (GeofenceRegistrar.hasRequiredPermissions(this)) GeofenceRegistrar.register(this, geofenceList)
                else toast("Geofences won't trigger without \"Allow all the time\"", long = true)
        }
    }

    private fun onForegroundLocationGranted() {
        if (locationFeaturesStarted) return
        locationFeaturesStarted = true
        // Android 14 throws if a location foreground service starts before permission is granted.
        startForegroundService(Intent(this, LocationService::class.java))
        GeofenceRegistrar.register(this, geofenceList)
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
        btnCreate.setOnClickListener { createGeofenceAtCurrentLocation() }
        findViewById<Button>(R.id.logoutButton).setOnClickListener { logout() }

        adapter = GeofenceAdapter(
            geofenceList,
            onDelete = ::confirmDelete,
            onClick = { area ->
                startActivity(
                    Intent(this, GeofenceDetailActivity::class.java)
                        .putExtra(GeofenceDetailActivity.EXTRA_GEOFENCE_ID, area.id)
                        .putExtra(GeofenceDetailActivity.EXTRA_GEOFENCE_NAME, area.name)
                )
            }
        )
        findViewById<RecyclerView>(R.id.recycler_geofences).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
    }

    /** One-shot location fix when needed, instead of polling GPS every few seconds. */
    @SuppressLint("MissingPermission") // checked by hasFineLocation()
    private fun createGeofenceAtCurrentLocation() {
        if (!hasFineLocation()) {
            requestForegroundPermissions()
            return
        }
        btnCreate.isEnabled = false
        toast("Getting your location...")
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, locationCancellation.token)
            .addOnSuccessListener { location ->
                if (location == null) toast("Couldn't get your location. Is location turned on?", long = true)
                else showNameDialog(location.latitude, location.longitude, slider.value)
            }
            .addOnFailureListener { toast("Couldn't get your location: ${it.message}") }
            .addOnCompleteListener { btnCreate.isEnabled = true }
    }

    private fun showNameDialog(lat: Double, lng: Double, radius: Float) {
        val input = EditText(this).apply { hint = "Enter area name" }
        AlertDialog.Builder(this)
            .setTitle("Name this Geofence")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("Name required")
                    return@setPositiveButton
                }
                val area = GeofenceArea(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    latitude = lat,
                    longitude = lng,
                    radius = radius,
                    isEnabled = true,
                    ownerId = userId
                )
                GeofenceRepository.saveArea(area)
                    .addOnSuccessListener {
                        toast("Saved")
                        geofenceList.add(area)
                        adapter.notifyItemInserted(geofenceList.size - 1)
                        GeofenceRegistrar.register(this, listOf(area))
                    }
                    .addOnFailureListener { toast("Save failed: ${it.message}") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Geofences ----

    /** Loads the signed-in user's geofences, shows them, and registers the enabled ones. */
    private fun loadGeofences() {
        GeofenceRepository.loadAreas(userId)
            .addOnSuccessListener { areas ->
                geofenceList.clear()
                geofenceList.addAll(areas)
                adapter.notifyDataSetChanged()
                GeofenceRegistrar.register(this, geofenceList)
            }
            .addOnFailureListener { toast("Failed to load geofences: ${it.message}") }
    }

    private fun confirmDelete(area: GeofenceArea) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${area.name}?")
            .setMessage("The files on your phone are kept, but they'll no longer be attached to this place.")
            .setPositiveButton("Delete") { _, _ -> deleteGeofence(area) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteGeofence(area: GeofenceArea) {
        GeofenceRepository.deleteArea(area.id, userId)
            .addOnSuccessListener {
                GeofenceRegistrar.remove(this, area.id)
                GeofenceStateStore.onExit(this, listOf(area.id))
                val idx = geofenceList.indexOfFirst { it.id == area.id }
                if (idx != -1) {
                    geofenceList.removeAt(idx)
                    adapter.notifyItemRemoved(idx)
                }
                toast("${area.name} deleted")
            }
            .addOnFailureListener { toast("Delete failed: ${it.message}") }
    }

    private fun toast(message: String, long: Boolean = false) =
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQ_FOREGROUND_PERMISSIONS = 1001
        const val REQ_BACKGROUND_LOCATION = 1002
    }
}
