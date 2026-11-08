package com.nimbus.spatial.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.slider.Slider
import com.google.firebase.auth.FirebaseAuth
import com.nimbus.spatial.R
import com.nimbus.spatial.data.GeofenceArea
import com.nimbus.spatial.data.GeofenceRepository
import com.nimbus.spatial.geofence.GeofenceRegistrar
import com.nimbus.spatial.geofence.GeofenceStateStore
import com.nimbus.spatial.geofence.LocationService
import com.nimbus.spatial.security.FileVault
import java.util.UUID

/** Home screen: create a geofence where you're standing, and list, open or delete your geofences. */
class MainActivity : AppCompatActivity() {

    private lateinit var userId: String
    private lateinit var adapter: GeofenceAdapter
    private lateinit var createButton: Button
    private lateinit var radiusSlider: Slider

    private val geofences = mutableListOf<GeofenceArea>()
    private val locationCancellation = CancellationTokenSource()
    private val permissions = LocationPermissions(
        activity = this,
        onForegroundGranted = { startLocationFeatures(askForBackground = true) },
        onBackgroundGranted = { GeofenceRegistrar.register(this, geofences) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            openLogin()
            return
        }
        userId = user.uid
        FileVault.clearDecrypted(this) // leftovers from a viewer the app never came back from

        setContentView(R.layout.activity_main)
        setUpViews()
        loadGeofences()

        // savedInstanceState is non-null after a rotation; don't re-show the permission dialog then.
        val firstLaunch = savedInstanceState == null
        if (permissions.hasForeground()) startLocationFeatures(askForBackground = firstLaunch)
        else if (firstLaunch) permissions.requestForeground()
    }

    override fun onDestroy() {
        locationCancellation.cancel()
        super.onDestroy()
    }

    private fun setUpViews() {
        val radiusLabel = findViewById<TextView>(R.id.tv_selected_radius)
        radiusSlider = findViewById(R.id.slider_radius)
        radiusSlider.addOnChangeListener { _, value, _ ->
            radiusLabel.text = "Selected Radius: ${value.toInt()} meters"
        }

        createButton = findViewById(R.id.btn_create_geofence)
        createButton.setOnClickListener { createGeofenceHere() }
        findViewById<Button>(R.id.btn_logout).setOnClickListener { logOut() }

        adapter = GeofenceAdapter(geofences, onClick = ::openGeofence, onDelete = ::confirmDelete)
        findViewById<RecyclerView>(R.id.recycler_geofences).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
    }

    /** Runs once location permission is granted. Starting the service earlier crashes on Android 14. */
    private fun startLocationFeatures(askForBackground: Boolean) {
        startForegroundService(Intent(this, LocationService::class.java))
        GeofenceRegistrar.register(this, geofences)
        if (askForBackground) permissions.requestBackgroundIfNeeded()
    }

    // ---- Geofences ----

    private fun loadGeofences() {
        GeofenceRepository.loadAreas(userId)
            .addOnSuccessListener { areas ->
                geofences.clear()
                geofences.addAll(areas)
                adapter.notifyDataSetChanged()
                // Re-registering is a safety net (e.g. after location was switched off). Repeated
                // ENTER events it causes are ignored by GeofenceBroadcastReceiver.
                GeofenceRegistrar.register(this, geofences)
            }
            .addOnFailureListener { toast("Failed to load geofences: ${it.message}") }
    }

    /** Takes one location fix, then asks for a name. */
    @SuppressLint("MissingPermission") // checked by hasForeground()
    private fun createGeofenceHere() {
        if (!permissions.hasForeground()) {
            permissions.requestForeground()
            return
        }
        createButton.isEnabled = false
        toast("Getting your location...")
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, locationCancellation.token)
            .addOnSuccessListener { location ->
                if (location == null) toast("Couldn't get your location. Is location turned on?", long = true)
                else askForName(location.latitude, location.longitude, radiusSlider.value)
            }
            .addOnFailureListener { toast("Couldn't get your location: ${it.message}") }
            .addOnCompleteListener { createButton.isEnabled = true }
    }

    private fun askForName(latitude: Double, longitude: Double, radius: Float) {
        val input = EditText(this).apply { hint = "Enter area name" }
        AlertDialog.Builder(this)
            .setTitle("Name this Geofence")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) toast("Name required")
                else saveGeofence(
                    GeofenceArea(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        latitude = latitude,
                        longitude = longitude,
                        radius = radius,
                        ownerId = userId
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveGeofence(area: GeofenceArea) {
        GeofenceRepository.saveArea(area)
            .addOnSuccessListener {
                geofences.add(area)
                adapter.notifyItemInserted(geofences.lastIndex)
                GeofenceRegistrar.register(this, listOf(area))
                toast("Saved")
            }
            .addOnFailureListener { toast("Save failed: ${it.message}") }
    }

    private fun openGeofence(area: GeofenceArea) {
        startActivity(GeofenceDetailActivity.intent(this, area))
    }

    private fun confirmDelete(area: GeofenceArea) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${area.name}?")
            .setMessage(
                "This permanently deletes North's encrypted copies of its files. " +
                    "Originals you kept in your gallery or files aren't touched."
            )
            .setPositiveButton("Delete") { _, _ -> deleteGeofence(area) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteGeofence(area: GeofenceArea) {
        GeofenceRepository.deleteArea(area.id, userId)
            .addOnSuccessListener {
                GeofenceRegistrar.remove(this, area.id)
                GeofenceStateStore.exit(this, listOf(area.id))
                val vault = FileVault(this, userId)
                Thread { vault.deleteArea(area.id) }.start() // disk I/O off the main thread
                val index = geofences.indexOfFirst { it.id == area.id }
                if (index != -1) {
                    geofences.removeAt(index)
                    adapter.notifyItemRemoved(index)
                }
                toast("${area.name} deleted")
            }
            .addOnFailureListener { toast("Delete failed: ${it.message}") }
    }

    // ---- Account ----

    private fun logOut() {
        // The next account on this phone must not inherit this user's fences or saved state.
        GeofenceRegistrar.removeAll(this)
        GeofenceStateStore.clear(this)
        FileVault.clearDecrypted(this)
        stopService(Intent(this, LocationService::class.java))
        FirebaseAuth.getInstance().signOut()
        openLogin()
    }

    private fun openLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish() // when called from onCreate, Android skips onStart/onResume entirely
    }
}
