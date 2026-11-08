package com.nimbus.spatial.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.firebase.auth.FirebaseAuth
import com.nimbus.spatial.R
import com.nimbus.spatial.geofence.GeofenceStateStore
import com.nimbus.spatial.wallpaper.Wallpaper
import com.nimbus.spatial.wallpaper.WallpaperSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Wallpaper options (on/off, which screens, which pictures) and the account. */
class SettingsActivity : AppCompatActivity() {

    /** The views for one wallpaper slot (view_wallpaper_choice.xml, included twice). */
    private class SlotViews(root: View) {
        val label: TextView = root.findViewById(R.id.tv_label)
        val preview: ImageView = root.findViewById(R.id.iv_preview)
        val choose: Button = root.findViewById(R.id.btn_choose)
        val reset: Button = root.findViewById(R.id.btn_reset)
    }

    private lateinit var wallpaperSwitch: MaterialSwitch
    private lateinit var wallpaperOptions: View
    private lateinit var slots: Map<Wallpaper.Slot, SlotViews>

    // One picker per slot, so the result always knows which slot it's for (even after rotation).
    private val pickInside = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) saveWallpaper(Wallpaper.Slot.INSIDE, uri)
    }
    private val pickOutside = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) saveWallpaper(Wallpaper.Slot.OUTSIDE, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        setUpWallpaperSwitch()
        setUpTargetToggle()
        setUpSlots()
        setUpAccount()
    }

    // ---- Wallpaper ----

    private fun setUpWallpaperSwitch() {
        wallpaperSwitch = findViewById(R.id.switch_wallpaper)
        wallpaperOptions = findViewById(R.id.group_wallpaper_options)

        val supported = Wallpaper.isSupported(this)
        findViewById<View>(R.id.tv_wallpaper_unsupported).isVisible = !supported
        wallpaperSwitch.isEnabled = supported
        wallpaperSwitch.isChecked = supported && WallpaperSettings.isEnabled(this)
        showOptionsEnabled(wallpaperSwitch.isChecked)

        wallpaperSwitch.setOnCheckedChangeListener { _, checked ->
            WallpaperSettings.setEnabled(this, checked)
            showOptionsEnabled(checked)
            if (checked) applyCurrentWallpaper()
        }
    }

    private fun setUpTargetToggle() {
        val buttonForTarget = mapOf(
            WallpaperSettings.Target.HOME to R.id.btn_target_home,
            WallpaperSettings.Target.LOCK to R.id.btn_target_lock,
            WallpaperSettings.Target.BOTH to R.id.btn_target_both
        )
        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.toggle_wallpaper_target)
        toggle.check(buttonForTarget.getValue(WallpaperSettings.target(this)))
        toggle.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val target = buttonForTarget.entries.first { it.value == buttonId }.key
            WallpaperSettings.setTarget(this, target)
            applyCurrentWallpaper()
        }
    }

    private fun setUpSlots() {
        slots = mapOf(
            Wallpaper.Slot.INSIDE to SlotViews(findViewById(R.id.wallpaper_inside)),
            Wallpaper.Slot.OUTSIDE to SlotViews(findViewById(R.id.wallpaper_outside))
        )
        val imagesOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        for ((slot, views) in slots) {
            views.label.setText(
                if (slot == Wallpaper.Slot.INSIDE) R.string.settings_wallpaper_inside else R.string.settings_wallpaper_outside
            )
            views.choose.setOnClickListener {
                if (slot == Wallpaper.Slot.INSIDE) pickInside.launch(imagesOnly) else pickOutside.launch(imagesOnly)
            }
            views.reset.setOnClickListener { resetWallpaper(slot) }
            showPreview(slot)
        }
    }

    /** Dims the options and blocks taps on them while the feature is off. */
    private fun showOptionsEnabled(enabled: Boolean) {
        wallpaperOptions.alpha = if (enabled) 1f else 0.38f
        setEnabledRecursively(wallpaperOptions, enabled)
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) setEnabledRecursively(view.getChildAt(i), enabled)
        }
    }

    private fun saveWallpaper(slot: Wallpaper.Slot, picture: Uri) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { Wallpaper.saveCustom(this@SettingsActivity, slot, picture) }
            if (!saved) {
                showMessage("North can't read that picture. Try a different one.")
                return@launch
            }
            showPreview(slot)
            applyIfShowing(slot)
        }
    }

    private fun resetWallpaper(slot: Wallpaper.Slot) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { Wallpaper.resetToDefault(this@SettingsActivity, slot) }
            showPreview(slot)
            applyIfShowing(slot)
        }
    }

    /** Shows the slot's picture in its preview; "Reset" only appears for the user's own pictures. */
    private fun showPreview(slot: Wallpaper.Slot) {
        val views = slots.getValue(slot)
        lifecycleScope.launch {
            val (bitmap, isCustom) = withContext(Dispatchers.IO) {
                // A small decode is enough for a 108 x 192 dp preview.
                Wallpaper.load(this@SettingsActivity, slot, 360, 640) to Wallpaper.hasCustom(this@SettingsActivity, slot)
            }
            views.preview.setImageBitmap(bitmap)
            views.reset.isVisible = isCustom
        }
    }

    /** If the changed slot is the one on screen right now, update the wallpaper straight away. */
    private fun applyIfShowing(slot: Wallpaper.Slot) {
        if (Wallpaper.Slot.forState(GeofenceStateStore.isInsideAny(this)) == slot) applyCurrentWallpaper()
    }

    private fun applyCurrentWallpaper() {
        if (!WallpaperSettings.isEnabled(this)) return
        val insideAnyPlace = GeofenceStateStore.isInsideAny(this)
        lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) { Wallpaper.applyFor(applicationContext, insideAnyPlace) }
            showMessage(if (changed) "Wallpaper updated" else "Couldn't change the wallpaper")
        }
    }

    // ---- Account ----

    private fun setUpAccount() {
        findViewById<TextView>(R.id.tv_account_email).text = FirebaseAuth.getInstance().currentUser?.email.orEmpty()
        findViewById<Button>(R.id.btn_logout).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_log_out)
                .setMessage("Your encrypted files stay on this phone and come back when you sign in again.")
                .setPositiveButton(R.string.settings_log_out) { _, _ -> Session.logOut(this) }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }
}
