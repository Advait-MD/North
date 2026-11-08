package com.nimbus.spatial.geofence

import android.content.Context
import android.content.SharedPreferences

/**
 * Saves which geofences the user is inside, so the state survives the app being killed
 * (the broadcast receiver often runs in a freshly started process).
 */
object GeofenceStateStore {
    private const val PREFS_NAME = "geofence_state"
    private const val KEY_INSIDE_IDS = "inside_fence_ids"
    private val lock = Any()

    fun isInside(context: Context, geofenceId: String): Boolean =
        synchronized(lock) { read(prefs(context)) }.contains(geofenceId)

    fun enter(context: Context, ids: Collection<String>): InsideStateChange =
        update(context) { GeofenceState.enter(it, ids) }

    fun exit(context: Context, ids: Collection<String>): InsideStateChange =
        update(context) { GeofenceState.exit(it, ids) }

    fun clear(context: Context) {
        synchronized(lock) { prefs(context).edit().remove(KEY_INSIDE_IDS).commit() }
    }

    /**
     * Calls [onChange] on the main thread whenever the state changes. Keep the returned listener
     * and pass it to [removeListener]: SharedPreferences only holds it weakly.
     */
    fun addListener(context: Context, onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_INSIDE_IDS) onChange() // key is null after clear()
        }
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun removeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun update(context: Context, transition: (Set<String>) -> InsideStateChange): InsideStateChange =
        synchronized(lock) {
            val prefs = prefs(context)
            val change = transition(read(prefs))
            // commit() rather than apply(): the receiver's process can be killed right after it finishes.
            prefs.edit().putStringSet(KEY_INSIDE_IDS, HashSet(change.after)).commit()
            change
        }

    // The set returned by getStringSet must not be modified, so copy it.
    private fun read(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_INSIDE_IDS, null)?.toSet() ?: emptySet()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
