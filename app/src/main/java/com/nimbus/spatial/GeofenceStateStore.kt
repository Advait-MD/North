package com.nimbus.spatial

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the set of geofences the user is inside, so the state survives process death
 * (the broadcast receiver often runs in a fresh process, and the activity may be killed).
 */
object GeofenceStateStore {
    private const val PREFS_NAME = "geofence_state"
    private const val KEY_INSIDE_IDS = "inside_fence_ids"
    private val lock = Any()

    fun isInside(context: Context, geofenceId: String): Boolean =
        insideIds(context).contains(geofenceId)

    fun insideIds(context: Context): Set<String> = synchronized(lock) { read(prefs(context)) }

    fun onEnter(context: Context, ids: Collection<String>) =
        update(context) { GeofenceState.onEnter(it, ids) }

    fun onExit(context: Context, ids: Collection<String>) =
        update(context) { GeofenceState.onExit(it, ids) }

    fun clear(context: Context) = synchronized(lock) {
        prefs(context).edit().remove(KEY_INSIDE_IDS).commit()
    }

    /** Calls [onChange] (on the main thread) whenever the inside-state changes. */
    fun addListener(context: Context, onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_INSIDE_IDS) onChange() // key is null after clear()
        }
        // SharedPreferences holds listeners weakly, so the caller must keep the returned reference.
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun removeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun update(context: Context, transform: (Set<String>) -> Set<String>): Set<String> =
        synchronized(lock) {
            val prefs = prefs(context)
            val updated = transform(read(prefs))
            // commit() rather than apply(): the receiver's process can die right after onReceive.
            prefs.edit().putStringSet(KEY_INSIDE_IDS, HashSet(updated)).commit()
            updated
        }

    // getStringSet's result must not be modified, so always copy it.
    private fun read(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_INSIDE_IDS, emptySet())?.toSet() ?: emptySet()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
