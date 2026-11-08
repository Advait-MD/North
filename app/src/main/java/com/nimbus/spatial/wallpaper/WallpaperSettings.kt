package com.nimbus.spatial.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences

/** The user's wallpaper choices from the settings screen. The images themselves are in [Wallpaper]. */
object WallpaperSettings {
    private const val PREFS_NAME = "wallpaper_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGET = "target"

    /** On by default, because changing the wallpaper was always North's behaviour. */
    private const val ENABLED_BY_DEFAULT = true

    /** Which screens get the wallpaper. */
    enum class Target(val wallpaperFlags: Int) {
        HOME(WallpaperManager.FLAG_SYSTEM),
        LOCK(WallpaperManager.FLAG_LOCK),
        BOTH(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, ENABLED_BY_DEFAULT)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun target(context: Context): Target {
        val saved = prefs(context).getString(KEY_TARGET, null)
        return Target.entries.firstOrNull { it.name == saved } ?: Target.BOTH
    }

    fun setTarget(context: Context, target: Target) {
        prefs(context).edit().putString(KEY_TARGET, target.name).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
