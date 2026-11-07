package com.nimbus.spatial

import android.content.Context

/**
 * Durable geofence state. Replaces the old in-memory MainActivity.enabledGeofenceIds,
 * which was wiped every time Android killed the app process.
 */
object GeofenceStateStore {
    private const val PREFS = "GeofencePrefs"
    private const val KEY_INSIDE = "insideFenceIds"
    private const val KEY_REGISTERED = "registeredFenceIds"
    private const val KEY_SYNC_LAT = "lastSyncLat"
    private const val KEY_SYNC_LNG = "lastSyncLng"
    private const val KEY_SYNC_AT = "lastSyncAt"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readSet(context: Context, key: String): Set<String> =
        // getStringSet's result must not be mutated, so always copy it.
        prefs(context).getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun writeSet(context: Context, key: String, value: Set<String>) {
        prefs(context).edit().putStringSet(key, value.toSet()).apply()
    }

    @Synchronized
    fun markInside(context: Context, id: String): Boolean {
        val current = readSet(context, KEY_INSIDE)
        if (id in current) return false
        writeSet(context, KEY_INSIDE, current + id)
        return true
    }

    @Synchronized
    fun markOutside(context: Context, id: String) {
        writeSet(context, KEY_INSIDE, readSet(context, KEY_INSIDE) - id)
    }

    fun isInside(context: Context, id: String) = id in readSet(context, KEY_INSIDE)

    fun isInsideAny(context: Context) = readSet(context, KEY_INSIDE).isNotEmpty()

    fun registeredIds(context: Context) = readSet(context, KEY_REGISTERED)

    fun setRegisteredIds(context: Context, ids: Set<String>) =
        writeSet(context, KEY_REGISTERED, ids)

    /**
     * Android drops every geofence on reboot, app update or when location is toggled.
     * Forget what we think is registered so the next sync re-adds everything.
     */
    fun resetRegistration(context: Context) {
        prefs(context).edit()
            .remove(KEY_REGISTERED)
            .remove(KEY_SYNC_AT)
            .apply()
    }

    fun lastSync(context: Context): Triple<Double, Double, Long>? {
        val p = prefs(context)
        val at = p.getLong(KEY_SYNC_AT, 0L)
        if (at == 0L) return null
        return Triple(
            Double.fromBits(p.getLong(KEY_SYNC_LAT, 0L)),
            Double.fromBits(p.getLong(KEY_SYNC_LNG, 0L)),
            at
        )
    }

    fun setLastSync(context: Context, lat: Double, lng: Double, at: Long) {
        prefs(context).edit()
            .putLong(KEY_SYNC_LAT, lat.toRawBits())
            .putLong(KEY_SYNC_LNG, lng.toRawBits())
            .putLong(KEY_SYNC_AT, at)
            .apply()
    }
}
