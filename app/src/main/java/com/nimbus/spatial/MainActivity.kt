package com.nimbus.spatial

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.slider.Slider
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /** Geofences are wiped on reboot/update; re-register everything once per process. */
        private var registrationResetThisProcess = false
    }

    private lateinit var adapter: GeofenceAdapter
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var slider: Slider
    private lateinit var tvRadius: TextView

    private val rows = mutableListOf<PlaceRow>()
    private var mine: List<GeofenceArea> = emptyList()
    private var nearbyPublic: List<GeofenceArea> = emptyList()
    private var currentLocation: Location? = null
    private var hasSyncedThisSession = false
    private var uiReady = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(res: LocationResult) {
            val loc = res.lastLocation ?: return
            currentLocation = loc
            if (!hasSyncedThisSession || PlaceSync.shouldRefresh(this@MainActivity, loc.latitude, loc.longitude)) {
                hasSyncedThisSession = true
                syncPlaces(loc)
            } else {
                renderRows()
            }
        }
    }

    private val foregroundPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            onLocationGranted()
        } else {
            Toast.makeText(this, "North needs location to unlock files at places", Toast.LENGTH_LONG).show()
        }
    }

    // Android 11+ ignores background location when it's requested together with foreground.
    private val backgroundPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Without \"Allow all the time\", places only unlock while North is open", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (FirebaseAuth.getInstance().currentUser == null) {
            // LoginActivity starts a fresh MainActivity once signed in.
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (!registrationResetThisProcess) {
            GeofenceStateStore.resetRegistration(this)
            registrationResetThisProcess = true
        }

        setupUI()
        loadMyPlaces()
        requestForegroundPermissions()
    }

    override fun onStart() {
        super.onStart()
        if (uiReady && FirebaseAuth.getInstance().currentUser != null) startLocationUpdates()
    }

    override fun onStop() {
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        super.onStop()
    }

    // region permissions

    private fun hasFineLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestForegroundPermissions() {
        if (hasFineLocation()) {
            onLocationGranted()
            return
        }
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        foregroundPermissions.launch(perms.toTypedArray())
    }

    private fun onLocationGranted() {
        startLocationService()
        startLocationUpdates()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AlertDialog.Builder(this)
                .setTitle("Unlock places in the background")
                .setMessage("To notice when you walk into a place while North is closed, choose \"Allow all the time\" on the next screen.")
                .setPositiveButton("Continue") { _, _ ->
                    backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    // endregion

    private fun setupUI() {
        slider = findViewById(R.id.seekbar_radius)
        tvRadius = findViewById(R.id.tv_selected_radius)
        tvRadius.text = "Selected Radius: ${slider.value.toInt()} meters"
        slider.addOnChangeListener { _, value, _ ->
            tvRadius.text = "Selected Radius: ${value.toInt()} meters"
        }

        findViewById<Button>(R.id.btn_create_geofence).setOnClickListener {
            val loc = currentLocation
            if (loc == null) {
                Toast.makeText(this, "Waiting for location...", Toast.LENGTH_SHORT).show()
            } else {
                showCreateDialog(loc, slider.value)
            }
        }

        findViewById<Button>(R.id.logoutButton).setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            stopService(Intent(this, LocationService::class.java))
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        adapter = GeofenceAdapter(
            rows,
            onDelete = { confirmDelete(it) },
            onClick = { place ->
                startActivity(Intent(this, GeofenceDetailActivity::class.java).apply {
                    putExtra(GeofenceDetailActivity.EXTRA_PLACE_ID, place.id)
                    putExtra(GeofenceDetailActivity.EXTRA_PLACE_NAME, place.name)
                })
            }
        )
        findViewById<RecyclerView>(R.id.recycler_geofences).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        uiReady = true
    }

    private fun startLocationService() {
        val intent = Intent(this, LocationService::class.java)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException if started from the background.
            Log.e(TAG, "Could not start location service", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasFineLocation()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L).build()
        fusedLocationClient.requestLocationUpdates(req, locationCallback, mainLooper)
    }

    // region places

    /** Shows the user's own places straight away, before the first location fix arrives. */
    private fun loadMyPlaces() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection(FirestorePaths.PLACES)
            .whereEqualTo("ownerId", uid)
            .get()
            .addOnSuccessListener { snap ->
                mine = snap.documents.mapNotNull { GeofenceArea.fromDoc(it) }
                renderRows()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load your places", Toast.LENGTH_SHORT).show()
            }
    }

    private fun syncPlaces(loc: Location) {
        PlaceSync.refresh(this, loc.latitude, loc.longitude,
            onResult = { result ->
                mine = result.mine
                nearbyPublic = result.nearbyPublic
                renderRows()
            },
            onError = {
                Toast.makeText(this, "Couldn't refresh places: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun renderRows() {
        val loc = currentLocation
        fun dist(p: GeofenceArea) =
            loc?.let { GeoMath.distanceMeters(it.latitude, it.longitude, p.latitude, p.longitude) }

        rows.clear()
        rows += mine.map { PlaceRow(it, isMine = true, distanceM = dist(it)) }
            .sortedBy { it.distanceM ?: Double.MAX_VALUE }
        rows += nearbyPublic.map { PlaceRow(it, isMine = false, distanceM = dist(it)) }
        adapter.notifyDataSetChanged()
    }

    private fun showCreateDialog(loc: Location, radius: Float) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val nameInput = EditText(this).apply { hint = "Place name" }
        val publicBox = CheckBox(this).apply {
            text = "Public: anyone standing here can open the files you mark public"
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(nameInput)
            addView(publicBox)
        }

        AlertDialog.Builder(this)
            .setTitle("New place")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                when {
                    name.isEmpty() -> Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show()
                    name.length > 80 -> Toast.makeText(this, "Name too long", Toast.LENGTH_SHORT).show()
                    uid == null -> Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
                    else -> createPlace(
                        PlaceRepository.newPlace(name, loc.latitude, loc.longitude, radius, uid, publicBox.isChecked),
                        loc
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createPlace(place: GeofenceArea, loc: Location) {
        PlaceRepository.savePlace(place)
            .addOnSuccessListener {
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                mine = mine + place
                renderRows()
                syncPlaces(loc) // registers the new geofence
            }
            .addOnFailureListener {
                Toast.makeText(this, "Save failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun confirmDelete(place: GeofenceArea) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${place.name}?")
            .setMessage("This permanently deletes the place and every file pinned to it.")
            .setPositiveButton("Delete") { _, _ -> deletePlace(place) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePlace(place: GeofenceArea) {
        PlaceRepository.deletePlace(place)
            .addOnSuccessListener {
                PlaceSync.unregister(this, place.id)
                mine = mine.filterNot { it.id == place.id }
                renderRows()
                Toast.makeText(this, "${place.name} deleted", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to delete place ${place.id}", it)
                Toast.makeText(this, "Delete failed: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // endregion
}
