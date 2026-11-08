package com.nimbus.spatial.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.nimbus.spatial.R
import com.nimbus.spatial.data.GeofenceArea
import com.nimbus.spatial.data.GeofenceRepository
import com.nimbus.spatial.geofence.GeofenceRegistrar
import com.nimbus.spatial.geofence.GeofenceStateStore
import com.nimbus.spatial.geofence.LocationService
import com.nimbus.spatial.security.FileVault
import java.util.UUID

/** Home screen: create a place where you're standing, and list, open or delete your places. */
class MainActivity : AppCompatActivity() {

    private lateinit var userId: String
    private lateinit var adapter: GeofenceAdapter
    private lateinit var createButton: MaterialButton
    private lateinit var radiusSlider: Slider
    private lateinit var emptyState: View
    private lateinit var permissionBanner: View
    private lateinit var permissionBannerText: TextView

    private val places = mutableListOf<GeofenceArea>()
    private val locationCancellation = CancellationTokenSource()
    private var stateListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var locationStarted = false
    private var registeredInBackground = false

    private val permissions = LocationPermissions(
        activity = this,
        onForegroundGranted = ::onLocationGranted,
        onBackgroundGranted = ::updatePermissionState
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish() // when called from onCreate, Android skips onStart/onResume entirely
            return
        }
        userId = user.uid
        FileVault.clearDecrypted(this) // leftovers from a viewer the app never came back from

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        setUpViews()
        loadPlaces()

        if (!permissions.hasForeground() && savedInstanceState == null) {
            // First launch: ask once. After that, the banner offers it instead of nagging.
            permissions.requestForeground()
        }
    }

    override fun onStart() {
        super.onStart()
        // Show "You're here" live as the user crosses places.
        refreshInsideState()
        stateListener = GeofenceStateStore.addListener(this, ::refreshInsideState)
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed permissions in system settings while we were away.
        if (permissions.hasForeground()) startLocationFeatures()
        updatePermissionState()
    }

    override fun onStop() {
        stateListener?.let { GeofenceStateStore.removeListener(this, it) }
        stateListener = null
        super.onStop()
    }

    override fun onDestroy() {
        locationCancellation.cancel()
        super.onDestroy()
    }

    private fun setUpViews() {
        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            } else {
                false
            }
        }

        permissionBanner = findViewById(R.id.card_permission_banner)
        permissionBannerText = findViewById(R.id.tv_permission_banner)
        findViewById<View>(R.id.btn_permission_banner).setOnClickListener {
            if (permissions.hasForeground()) permissions.requestBackgroundIfNeeded() else permissions.requestForeground()
        }

        val radiusValue = findViewById<TextView>(R.id.tv_radius_value)
        radiusSlider = findViewById(R.id.slider_radius)
        radiusValue.text = getString(R.string.main_radius_value, radiusSlider.value.toInt())
        radiusSlider.addOnChangeListener { _, value, _ ->
            radiusValue.text = getString(R.string.main_radius_value, value.toInt())
        }

        createButton = findViewById(R.id.btn_create_place)
        createButton.setOnClickListener { createPlaceHere() }

        emptyState = findViewById(R.id.empty_state)
        adapter = GeofenceAdapter(places, onClick = ::openPlace, onDelete = ::confirmDelete)
        findViewById<RecyclerView>(R.id.recycler_places).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
    }

    // ---- Location and permissions ----

    private fun onLocationGranted() {
        startLocationFeatures()
        updatePermissionState()
        permissions.requestBackgroundIfNeeded() // the user is already in the permission flow
    }

    /** Only after location permission is granted: starting the service earlier crashes on Android 14. */
    private fun startLocationFeatures() {
        if (locationStarted) return
        locationStarted = true
        startForegroundService(Intent(this, LocationService::class.java))
    }

    /** Shows or hides the permission banner, and registers places once background access arrives. */
    private fun updatePermissionState() {
        val canRegister = GeofenceRegistrar.canRegister(this)
        permissionBanner.isVisible = !canRegister
        permissionBannerText.setText(
            if (permissions.hasForeground()) R.string.main_banner_background else R.string.main_banner_location
        )
        if (canRegister && !registeredInBackground) {
            registeredInBackground = true
            GeofenceRegistrar.register(this, places)
        }
    }

    private fun refreshInsideState() {
        adapter.insideIds = GeofenceStateStore.insideIds(this)
    }

    // ---- Places ----

    private fun loadPlaces() {
        GeofenceRepository.loadAreas(userId)
            .addOnSuccessListener { loaded ->
                places.clear()
                places.addAll(loaded.sortedBy { it.name.lowercase() })
                adapter.notifyDataSetChanged()
                emptyState.isVisible = places.isEmpty()
                // Re-registering is a safety net (e.g. after location was switched off). The repeated
                // ENTER events it causes are ignored by GeofenceBroadcastReceiver.
                GeofenceRegistrar.register(this, places)
            }
            .addOnFailureListener { showMessage("Couldn't load your places: ${it.message}", long = true) }
    }

    /** Takes one location fix, then asks for a name. */
    @SuppressLint("MissingPermission") // checked by hasForeground()
    private fun createPlaceHere() {
        if (!permissions.hasForeground()) {
            permissions.requestForeground()
            return
        }
        setLocating(true)
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, locationCancellation.token)
            .addOnSuccessListener { location ->
                if (location == null) showMessage("Couldn't get your location. Is location turned on?", long = true)
                else askForName(location.latitude, location.longitude, radiusSlider.value)
            }
            .addOnFailureListener { showMessage("Couldn't get your location: ${it.message}") }
            .addOnCompleteListener { setLocating(false) }
    }

    private fun setLocating(locating: Boolean) {
        createButton.isEnabled = !locating
        createButton.setText(if (locating) R.string.main_locating else R.string.main_create_place)
    }

    private fun askForName(latitude: Double, longitude: Double, radius: Float) {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_place_name, null)
        val nameLayout = content.findViewById<TextInputLayout>(R.id.til_place_name)
        val nameInput = content.findViewById<TextInputEditText>(R.id.et_place_name)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_place_name_title)
            .setView(content)
            .setPositiveButton(R.string.dialog_save, null) // set below, so an empty name keeps the dialog open
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = nameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                nameLayout.error = "Give this place a name"
                return@setOnClickListener
            }
            dialog.dismiss()
            savePlace(
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
        nameInput.requestFocus()
    }

    private fun savePlace(place: GeofenceArea) {
        GeofenceRepository.saveArea(place)
            .addOnSuccessListener {
                places.add(place)
                adapter.notifyItemInserted(places.lastIndex)
                emptyState.isVisible = false
                GeofenceRegistrar.register(this, listOf(place))
                showMessage("${place.name} saved")
            }
            .addOnFailureListener { showMessage("Couldn't save: ${it.message}", long = true) }
    }

    private fun openPlace(place: GeofenceArea) {
        startActivity(GeofenceDetailActivity.intent(this, place))
    }

    private fun confirmDelete(place: GeofenceArea) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${place.name}?")
            .setMessage(
                "This permanently deletes North's encrypted copies of its files. " +
                    "Originals you kept in your gallery or files aren't touched."
            )
            .setPositiveButton("Delete") { _, _ -> deletePlace(place) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun deletePlace(place: GeofenceArea) {
        GeofenceRepository.deleteArea(place.id, userId)
            .addOnSuccessListener {
                GeofenceRegistrar.remove(this, place.id)
                GeofenceStateStore.exit(this, listOf(place.id))
                val vault = FileVault(this, userId)
                Thread { vault.deleteArea(place.id) }.start() // disk I/O off the main thread
                val index = places.indexOfFirst { it.id == place.id }
                if (index != -1) {
                    places.removeAt(index)
                    adapter.notifyItemRemoved(index)
                }
                emptyState.isVisible = places.isEmpty()
                showMessage("${place.name} deleted")
            }
            .addOnFailureListener { showMessage("Couldn't delete: ${it.message}", long = true) }
    }
}
